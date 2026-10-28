package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "cloned_repos",
    indices = [Index(value = ["owner", "repoName"])]
)
data class ClonedRepoEntity(
    @PrimaryKey val id: String, // e.g. "owner_repo"
    val owner: String,
    val repoName: String,
    val repoUrl: String,
    val branch: String = "main",
    val defaultBranch: String = "main",
    val commitSha: String? = null,
    val localDirPath: String,
    val fileCount: Int = 0,
    val skillCount: Int = 0,
    val discoveredSkillsJson: String = "[]",
    val isPrivate: Boolean = false,
    val lastSyncStatus: String = "SUCCESS", // "SUCCESS", "SYNCING", "FAILED"
    val lastSyncMessage: String = "",
    val clonedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getDiscoveredSkills(): List<DiscoveredSkillSummary> {
        return runCatching {
            val arr = JSONArray(discoveredSkillsJson)
            val list = mutableListOf<DiscoveredSkillSummary>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                if (obj != null) {
                    list.add(
                        DiscoveredSkillSummary(
                            id = obj.optString("id"),
                            name = obj.optString("name"),
                            relativePath = obj.optString("relativePath"),
                            description = obj.optString("description"),
                            estimatedTokens = obj.optInt("estimatedTokens", 0)
                        )
                    )
                }
            }
            list
        }.getOrDefault(emptyList())
    }
}

data class DiscoveredSkillSummary(
    val id: String,
    val name: String,
    val relativePath: String,
    val description: String,
    val estimatedTokens: Int
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("relativePath", relativePath)
            put("description", description)
            put("estimatedTokens", estimatedTokens)
        }
    }
}
