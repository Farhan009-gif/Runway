package com.example.data.git

import android.content.Context
import android.util.Log
import com.example.data.local.ClonedRepoEntity
import com.example.data.local.DiscoveredSkillSummary
import com.example.data.local.SkillEntity
import com.example.data.mcp.SkillMarkdownParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class GitHubRepoTarget(
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val fullUrl: String
)

data class CloneRepoResult(
    val repoEntity: ClonedRepoEntity,
    val discoveredSkills: List<SkillEntity>,
    val fileCount: Int,
    val topLevelFiles: List<String>
)

object GitRepoCloner {
    private const val TAG = "GitRepoCloner"
    private const val MAX_EXTRACT_BYTES = 100 * 1024 * 1024L // 100MB limit for safety
    private const val MAX_SINGLE_FILE_BYTES = 10 * 1024 * 1024L // 10MB limit per file

    /**
     * Parses multiple formats of GitHub repository references:
     * - "https://github.com/owner/repo"
     * - "https://github.com/owner/repo.git"
     * - "https://github.com/owner/repo/tree/dev" (extracts branch 'dev')
     * - "github.com/owner/repo"
     * - "owner/repo"
     * - "git@github.com:owner/repo.git"
     */
    fun parseRepoUrl(rawInput: String): GitHubRepoTarget? {
        val trimmed = rawInput.trim()
            .removePrefix("git@github.com:")
            .removeSuffix(".git")
            .removeSuffix("/")

        if (trimmed.isEmpty()) return null

        val urlClean = when {
            trimmed.startsWith("https://github.com/", ignoreCase = true) ->
                trimmed.substring("https://github.com/".length)
            trimmed.startsWith("http://github.com/", ignoreCase = true) ->
                trimmed.substring("http://github.com/".length)
            trimmed.startsWith("github.com/", ignoreCase = true) ->
                trimmed.substring("github.com/".length)
            else -> trimmed
        }

        val parts = urlClean.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return null

        val owner = parts[0]
        val repo = parts[1]
        var branch: String? = null

        // Handle /tree/{branch_name}
        if (parts.size >= 4 && parts[2].equals("tree", ignoreCase = true)) {
            branch = parts.drop(3).joinToString("/")
        }

        val canonicalUrl = "https://github.com/$owner/$repo"
        return GitHubRepoTarget(
            owner = owner,
            repo = repo,
            branch = branch,
            fullUrl = canonicalUrl
        )
    }

    /**
     * Clones or re-syncs a GitHub repository by downloading the source archive (zipball),
     * safely extracting it into internal storage, and indexing agent skills.
     */
    suspend fun cloneOrSync(
        context: Context,
        okHttpClient: OkHttpClient,
        target: GitHubRepoTarget,
        personalAccessToken: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<CloneRepoResult> = withContext(Dispatchers.IO) {
        runCatching {
            val owner = target.owner
            val repo = target.repo
            val repoId = "${owner}_$repo"
            val reposDir = File(context.filesDir, "cloned_repos")
            if (!reposDir.exists()) reposDir.mkdirs()

            val targetDir = File(reposDir, repoId)
            val cleanToken = personalAccessToken?.trim()?.takeIf { it.isNotEmpty() }

            onProgress?.invoke("Connecting to GitHub for $owner/$repo...")

            // Try candidate branches if branch not explicitly provided
            val candidateBranches = if (target.branch.isNullOrBlank()) {
                listOf("main", "master", "develop")
            } else {
                listOf(target.branch)
            }

            var downloadSuccess = false
            var activeBranch = candidateBranches.first()
            var lastError: Exception? = null

            for (branchCandidate in candidateBranches) {
                try {
                    onProgress?.invoke("Downloading archive for branch '$branchCandidate'...")
                    val streamResult = fetchZipStream(
                        okHttpClient = okHttpClient,
                        owner = owner,
                        repo = repo,
                        branch = branchCandidate,
                        token = cleanToken
                    )

                    if (streamResult != null) {
                        activeBranch = branchCandidate
                        // Clean existing target dir before extraction
                        if (targetDir.exists()) {
                            targetDir.deleteRecursively()
                        }
                        targetDir.mkdirs()

                        onProgress?.invoke("Extracting files...")
                        extractZipSafely(streamResult, targetDir)
                        downloadSuccess = true
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Branch '$branchCandidate' download failed: ${e.message}")
                    lastError = e
                }
            }

            if (!downloadSuccess) {
                throw lastError ?: IllegalStateException("Unable to download repository archive from GitHub for $owner/$repo. Check repository name, branch, or access token.")
            }

            onProgress?.invoke("Scanning repository for agent skills (SKILL.md)...")

            // Index all files
            val allExtractedFiles = mutableListOf<File>()
            targetDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    allExtractedFiles.add(file)
                }
            }

            val topLevelFiles = targetDir.listFiles()?.map { it.name } ?: emptyList()

            // Discover and parse skills
            val discoveredSkills = scanForSkills(
                repoDir = targetDir,
                owner = owner,
                repo = repo,
                branch = activeBranch
            )

            onProgress?.invoke("Found ${discoveredSkills.size} skills in $owner/$repo.")

            val summaries = discoveredSkills.map { skill ->
                val relPath = skill.sourceUrl.substringAfter("/blob/$activeBranch/", skill.sourceUrl)
                DiscoveredSkillSummary(
                    id = skill.id,
                    name = skill.name,
                    relativePath = relPath,
                    description = skill.description,
                    estimatedTokens = skill.estimatedTokens
                )
            }

            val summariesJson = JSONArray().apply {
                summaries.forEach { put(it.toJson()) }
            }.toString()

            val now = System.currentTimeMillis()
            val repoEntity = ClonedRepoEntity(
                id = repoId,
                owner = owner,
                repoName = repo,
                repoUrl = target.fullUrl,
                branch = activeBranch,
                defaultBranch = activeBranch,
                commitSha = null,
                localDirPath = targetDir.absolutePath,
                fileCount = allExtractedFiles.size,
                skillCount = discoveredSkills.size,
                discoveredSkillsJson = summariesJson,
                isPrivate = cleanToken != null,
                lastSyncStatus = "SUCCESS",
                lastSyncMessage = "Cloned ${allExtractedFiles.size} files, found ${discoveredSkills.size} skills",
                clonedAt = now,
                updatedAt = now
            )

            CloneRepoResult(
                repoEntity = repoEntity,
                discoveredSkills = discoveredSkills,
                fileCount = allExtractedFiles.size,
                topLevelFiles = topLevelFiles
            )
        }
    }

    private fun fetchZipStream(
        okHttpClient: OkHttpClient,
        owner: String,
        repo: String,
        branch: String,
        token: String?
    ): InputStream? {
        // Option 1: GitHub API zipball (recommended when token present or for public repos)
        val apiZipUrl = "https://api.github.com/repos/$owner/$repo/zipball/$branch"
        val reqBuilder = Request.Builder()
            .url(apiZipUrl)
            .header("User-Agent", "OmniAI-Studio-Android")
            .header("Accept", "application/vnd.github+json")

        if (!token.isNullOrBlank()) {
            reqBuilder.header("Authorization", "Bearer $token")
        }

        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        if (response.isSuccessful && response.body != null) {
            return response.body!!.byteStream()
        }
        response.close()

        // Option 2: Fallback to codeload archive URL for public repositories
        val codeloadUrl = "https://codeload.github.com/$owner/$repo/zip/refs/heads/$branch"
        val codeloadReq = Request.Builder()
            .url(codeloadUrl)
            .header("User-Agent", "OmniAI-Studio-Android")
            .build()

        val codeloadResp = okHttpClient.newCall(codeloadReq).execute()
        if (codeloadResp.isSuccessful && codeloadResp.body != null) {
            return codeloadResp.body!!.byteStream()
        }
        codeloadResp.close()

        return null
    }

    /**
     * Safely extracts zip stream while preventing ZipSlip vulnerabilities,
     * stripping GitHub's top-level archive directory prefix (e.g. 'owner-repo-sha/...'),
     * and bounding resource consumption.
     */
    private fun extractZipSafely(inputStream: InputStream, destinationDir: File) {
        val destCanonicalPath = destinationDir.canonicalPath
        var totalBytesRead = 0L

        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val rawName = entry.name
                // Strip the GitHub root wrapper (e.g. "owner-repo-78f2a1b/src/...")
                val strippedPath = if (rawName.contains("/")) {
                    rawName.substringAfter("/")
                } else {
                    "" // Top-level container folder
                }

                if (strippedPath.isNotBlank()) {
                    val targetFile = File(destinationDir, strippedPath)
                    val targetCanonicalPath = targetFile.canonicalPath

                    // ZipSlip security check
                    if (!targetCanonicalPath.startsWith(destCanonicalPath)) {
                        Log.w(TAG, "Zip entry '$rawName' tried to escape destination directory!")
                        zis.closeEntry()
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()

                        // Avoid extracting enormous binaries (>10MB) or excessive overall archive
                        if (entry.size > MAX_SINGLE_FILE_BYTES) {
                            Log.w(TAG, "Skipping oversize file: $strippedPath (${entry.size} bytes)")
                        } else {
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                var fileBytesRead = 0L
                                while (zis.read(buffer).also { len = it } > 0) {
                                    totalBytesRead += len
                                    fileBytesRead += len
                                    if (totalBytesRead > MAX_EXTRACT_BYTES) {
                                        throw IllegalStateException("Repository archive exceeds maximum limit of 100MB.")
                                    }
                                    if (fileBytesRead > MAX_SINGLE_FILE_BYTES) {
                                        break
                                    }
                                    fos.write(buffer, 0, len)
                                }
                            }
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Recursively scans the cloned directory for SKILL.md instruction packs.
     * If no explicit SKILL.md is present, generates an intelligent Codebase Context Skill
     * from the repository's README, architecture docs, and file structure.
     */
    fun scanForSkills(
        repoDir: File,
        owner: String,
        repo: String,
        branch: String
    ): List<SkillEntity> {
        val skills = mutableListOf<SkillEntity>()

        // 1. Search for SKILL.md files (case-insensitive) across the repo
        repoDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val fileName = file.name
                val isSkillFile = fileName.equals("SKILL.md", ignoreCase = true) ||
                        fileName.endsWith(".skill.md", ignoreCase = true) ||
                        (file.parentFile?.name.equals("skills", ignoreCase = true) && fileName.endsWith(".md", ignoreCase = true))

                if (isSkillFile) {
                    runCatching {
                        val content = file.readText()
                        val relativePath = file.relativeTo(repoDir).path

                        // Deriving a friendly fallback name from the folder or file
                        val parentFolderName = file.parentFile?.name ?: repo
                        val fallbackName = if (parentFolderName.equals("skills", ignoreCase = true) || parentFolderName == repo) {
                            file.nameWithoutExtension.removeSuffix(".skill")
                        } else {
                            parentFolderName
                        }

                        val parsed = SkillMarkdownParser.parseSkillMarkdown(
                            rawInput = content,
                            fallbackName = fallbackName,
                            fallbackDescription = "Agent skill from GitHub repository $owner/$repo ($relativePath)"
                        )

                        val skillId = "skill_git_${owner}_${repo}_${sanitizeSkillId(fallbackName)}"
                        val sourceUrl = "https://github.com/$owner/$repo/blob/$branch/$relativePath"

                        val skillEntity = SkillEntity(
                            id = skillId,
                            name = "[$repo] ${parsed.name}",
                            description = parsed.description,
                            rawMarkdown = parsed.canonicalRawMarkdown,
                            bodyInstructions = parsed.bodyInstructions,
                            sourceUrl = sourceUrl,
                            isEnabled = true,
                            estimatedTokens = parsed.estimatedTokens,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        skills.add(skillEntity)
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to parse skill file: ${file.absolutePath}", e)
                    }
                }
            }
        }

        // 2. If no explicit SKILL.md was found in the entire repository,
        // create a synthesized Codebase Skill from the repository structure & README
        if (skills.isEmpty()) {
            val synthesizedSkill = createRepositoryContextSkill(repoDir, owner, repo, branch)
            if (synthesizedSkill != null) {
                skills.add(synthesizedSkill)
            }
        }

        return skills
    }

    /**
     * Synthesizes an agent skill providing deep codebase context for any cloned GitHub repository.
     */
    private fun createRepositoryContextSkill(
        repoDir: File,
        owner: String,
        repo: String,
        branch: String
    ): SkillEntity? {
        val readmeFile = repoDir.listFiles()?.firstOrNull {
            it.name.startsWith("README", ignoreCase = true) && it.isFile
        }

        val readmeSnippet = runCatching {
            readmeFile?.readText()?.take(2000)
        }.getOrNull() ?: "GitHub repository $owner/$repo"

        // Build a concise file tree summary
        val treeSummary = StringBuilder()
        val topFiles: List<File> = repoDir.listFiles()?.take(30) ?: emptyList()
        for (f in topFiles) {
            if (f.isDirectory) {
                treeSummary.appendLine("  📁 ${f.name}/")
                val subFiles: List<File> = f.listFiles()?.take(8) ?: emptyList()
                subFiles.forEach { sub ->
                    treeSummary.appendLine("    - ${sub.name}${if (sub.isDirectory) "/" else ""}")
                }
            } else {
                treeSummary.appendLine("  📄 ${f.name}")
            }
        }

        val body = buildString {
            appendLine("# Codebase Skill: $owner/$repo")
            appendLine()
            appendLine("You have direct access to the cloned repository '$owner/$repo' on branch '$branch'.")
            appendLine()
            appendLine("## Repository Overview")
            appendLine(readmeSnippet.trim())
            appendLine()
            appendLine("## Directory Structure")
            appendLine(treeSummary.toString().trim())
            appendLine()
            appendLine("## Instructions for AI Agent")
            appendLine("- Reference this codebase structure when helping the user write, refactor, or debug code.")
            appendLine("- Adopt the conventions, dependencies, and architecture evident in this repository.")
        }

        val parsed = SkillMarkdownParser.parseSkillMarkdown(
            rawInput = body,
            fallbackName = "$repo-codebase",
            fallbackDescription = "Codebase context, architecture, and guides for $owner/$repo"
        )

        val skillId = "skill_git_${owner}_${repo}_codebase"
        val sourceUrl = "https://github.com/$owner/$repo/tree/$branch"

        return SkillEntity(
            id = skillId,
            name = "[$repo] codebase",
            description = parsed.description,
            rawMarkdown = parsed.canonicalRawMarkdown,
            bodyInstructions = parsed.bodyInstructions,
            sourceUrl = sourceUrl,
            isEnabled = true,
            estimatedTokens = parsed.estimatedTokens,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * Lists relative paths of files in the cloned repository.
     */
    fun listRepoFiles(context: Context, repoId: String): List<String> {
        val targetDir = File(File(context.filesDir, "cloned_repos"), repoId)
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()

        return targetDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(targetDir).path }
            .sorted()
            .toList()
    }

    /**
     * Reads the text content of a specific file inside a cloned repository.
     */
    fun readRepoFile(context: Context, repoId: String, relativePath: String): String? {
        val targetDir = File(File(context.filesDir, "cloned_repos"), repoId)
        val file = File(targetDir, relativePath)
        val canonicalDest = targetDir.canonicalPath
        if (!file.canonicalPath.startsWith(canonicalDest)) return null
        if (!file.exists() || !file.isFile) return null

        return runCatching {
            file.readText().take(50000) // 50KB view limit
        }.getOrNull()
    }

    /**
     * Deletes the cloned repository files from device storage.
     */
    fun deleteClonedRepoFiles(context: Context, repoId: String): Boolean {
        val targetDir = File(File(context.filesDir, "cloned_repos"), repoId)
        return if (targetDir.exists()) {
            targetDir.deleteRecursively()
        } else {
            true
        }
    }

    private fun sanitizeSkillId(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "_").take(32)
    }
}
