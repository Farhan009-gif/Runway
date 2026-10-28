package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.video.DistributionPlatformPreset
import com.example.data.video.SpecializedVideoCategory
import com.example.data.video.VideoCreationModality
import com.example.data.video.VideoProductionCapabilitiesManager
import com.example.data.video.VideoStylePreset
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoProductionSuiteSection(
    currentPrompt: String,
    onApplyPromptAndSettings: (
        synthesizedPrompt: String,
        negativePrompt: String,
        aspectRatio: String,
        resolution: String,
        durationSeconds: Int,
        cameraMotion: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val manager = remember { VideoProductionCapabilitiesManager() }
    val state by manager.state.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Processing & Interactive inputs
    var objectRemovalInput by rememberSaveable { mutableStateOf("") }
    var bgSwapInput by rememberSaveable { mutableStateOf("Minimalist architectural glass loft at golden hour") }
    var voiceScriptInput by rememberSaveable { mutableStateOf(state.audioConfig.voiceScriptText) }
    var newCommentText by rememberSaveable { mutableStateOf("") }
    var newCommentTimecode by rememberSaveable { mutableStateOf("00:03") }

    MinimalCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("video_production_suite_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Top Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.MovieCreation,
                        contentDescription = "AI Video Production Studio",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "AI Video Production & Post-Processing Suite",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.statusBanner,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 6 Tab Chips covering all 12 Video Generation & Production Capabilities
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    "1. Creation & Templates",
                    "2. Storyboard & Captions",
                    "3. Processing & 4K/8K",
                    "4. Voice, Audio & Interactive",
                    "5. Multi-Platform & SEO",
                    "6. Collab, Analytics & QA"
                )
                tabs.forEachIndexed { index, title ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title, maxLines = 1) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("video_suite_tab_$index")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            when (selectedTab) {
                // ============================================================
                // TAB 0: 1. AI Creation Modalities, 5. Templates/Brand Kit & 10. Specialized Types
                // ============================================================
                0 -> {
                    Text(
                        text = "AI Creation Modality (Text / Image / Voice / Style Transfer)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VideoCreationModality.entries.forEach { mod ->
                            FilterChip(
                                selected = state.modality == mod,
                                onClick = { manager.selectModality(mod) },
                                label = { Text(mod.label, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                    Text(
                        text = state.modality.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Artistic Style Transfer Presets",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VideoStylePreset.entries.forEach { style ->
                            FilterChip(
                                selected = state.stylePreset == style,
                                onClick = { manager.selectStylePreset(style) },
                                label = { Text(style.label, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Specialized Video Templates & Brand Kit",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecializedVideoCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = state.specializedCategory == cat,
                                onClick = { manager.selectSpecializedCategory(cat) },
                                label = { Text("${cat.label} (${cat.defaultAspectRatio})", maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Brand Kit: ${state.brandKit.brandName} (${state.brandKit.primaryHex} · ${state.brandKit.accentHex})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Dynamic Text Overlay: \"${state.brandKit.dynamicTextOverlay}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ============================================================
                // TAB 1: 2. Content Creation Tools (Scene Gen, Storyboard, Editing Assistant, Captions)
                // ============================================================
                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Multi-Scene Storyboard & Automatic Captioning",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    manager.generateStoryboardFromScript(currentPrompt)
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Generate Scenes", maxLines = 1)
                        }
                    }

                    state.storyboardNodes.forEach { scene ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = scene.shotTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${scene.cameraMotion} · ${scene.durationSeconds}s",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = scene.visualPrompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = scene.captionLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Text(
                        text = "AI Video Editing Assistant Suggestions",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.editingSuggestions.forEach { sug ->
                        Text(
                            text = "• [${sug.category}] ${sug.recommendation} (${sug.impactLabel})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ============================================================
                // TAB 2: 3. Advanced Video Processing & 11. Technical Specifications (4K/8K, 60fps, Cloud)
                // ============================================================
                2 -> {
                    Text(
                        text = "Resolution, Frame Rate & Cloud Encoding (4K / 8K UHD)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1080p FHD", "4K UHD", "8K Ultra-HD").forEach { res ->
                            FilterChip(
                                selected = state.techSpecs.resolution == res,
                                onClick = { manager.updateTechSpecs(resolution = res) },
                                label = { Text(res, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                        listOf("24fps", "30fps", "60fps", "120fps").forEach { fps ->
                            FilterChip(
                                selected = state.techSpecs.frameRate == fps,
                                onClick = { manager.updateTechSpecs(frameRate = fps) },
                                label = { Text(fps, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("H.264 / AVC", "H.265 / HEVC 10-bit", "AV1 Ultra", "ProRes 422").forEach { codec ->
                            FilterChip(
                                selected = state.techSpecs.codec == codec,
                                onClick = { manager.updateTechSpecs(codec = codec) },
                                label = { Text(codec, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "AI Upscaling, Object Removal, Background Swap & Color Grading",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Rec.709 Cinema Neutral",
                            "Teal & Orange Blockbuster",
                            "Kodak 2383 Warm Film",
                            "HDR10 High Dynamic"
                        ).forEach { lut ->
                            FilterChip(
                                selected = state.processingConfig.colorGradingLut == lut,
                                onClick = { manager.updateProcessingConfig(colorLut = lut) },
                                label = { Text(lut, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = objectRemovalInput,
                        onValueChange = {
                            objectRemovalInput = it
                            manager.updateProcessingConfig(objectRemoval = it)
                        },
                        label = { Text("Object Removal Target (e.g., background wires, boom mic, crowd)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = bgSwapInput,
                        onValueChange = {
                            bgSwapInput = it
                            manager.updateProcessingConfig(bgSwap = it)
                        },
                        label = { Text("Background Replacement Environment") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ============================================================
                // TAB 3: 4. Interactive Video Features & 9. Voice & Audio Enhancement
                // ============================================================
                3 -> {
                    Text(
                        text = "Voice Cloning, AI Audio Enhancement & Contextual SFX",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Studio Narrator (Warm Baritone)",
                            "Conversational Host (Bright)",
                            "Documentary Authority",
                            "Energetic Product Presenter"
                        ).forEach { voice ->
                            FilterChip(
                                selected = state.audioConfig.voiceCloneProfile == voice,
                                onClick = { manager.updateAudioConfig(voiceProfile = voice) },
                                label = { Text(voice, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = voiceScriptInput,
                        onValueChange = {
                            voiceScriptInput = it
                            manager.updateAudioConfig(scriptText = it)
                        },
                        label = { Text("Voiceover Script (Voice-to-Video & Lip/Motion Sync)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI Noise/Reverb Removal & Contextual SFX",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = state.audioConfig.aiNoiseReductionEnabled,
                            onCheckedChange = {
                                manager.updateAudioConfig(noiseReduction = it)
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Interactive Hotspots, Branching & Personalized Variables",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Viewer Tokens: ${state.interactiveConfig.viewerPersonalizationVariables.entries.joinToString(" · ") { "{{${it.key}}}=${it.value}" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    state.interactiveConfig.hotspots.forEach { spot ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "[00:0${spot.timestampSeconds}s] Hotspot: \"${spot.label}\" → ${spot.targetBranchOrUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ============================================================
                // TAB 4: 6. Multi-Platform Distribution, Format Conversion, Thumbnails & SEO
                // ============================================================
                4 -> {
                    Text(
                        text = "Multi-Platform Format Conversion & Bitrate Optimization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DistributionPlatformPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.distribution.selectedPlatform == preset,
                                onClick = { manager.selectDistributionPlatform(preset) },
                                label = { Text("${preset.platformName} (${preset.aspectRatio})", maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    val activePlat = state.distribution.selectedPlatform
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${activePlat.platformName} Spec: ${activePlat.recommendedResolution} · ${activePlat.frameRate} · ${activePlat.maxBitrateMbps} Mbps",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Auto-Thumbnail Prompt: ${state.distribution.autoThumbnailPrompt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "SEO Title: ${state.distribution.seoTitle}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Tags: ${state.distribution.seoTags.joinToString(" ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // ============================================================
                // TAB 5: 7. Collaboration, 8. Analytics (A/B & ROI) & 12. Quality Assurance
                // ============================================================
                5 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Team Approval Status: ${state.approvalStage.label}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${state.revisions.size} tracked revisions · ${state.comments.size} frame comments",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { manager.advanceApprovalStage() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Advance Stage", maxLines = 1)
                        }
                    }

                    state.comments.forEach { c ->
                        Text(
                            text = "• [${c.timecodeLabel}] ${c.author}: \"${c.comment}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newCommentTimecode,
                            onValueChange = { newCommentTimecode = it },
                            label = { Text("Time") },
                            singleLine = true,
                            modifier = Modifier.width(88.dp)
                        )
                        OutlinedTextField(
                            value = newCommentText,
                            onValueChange = { newCommentText = it },
                            label = { Text("Add frame review comment...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                manager.addCollaborationComment("Reviewer", newCommentTimecode, newCommentText)
                                newCommentText = ""
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Post")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Performance Analytics, A/B Testing & ROI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Predicted Retention: ${state.analytics.predictedRetentionPercent}% · A/B Score: Variant A (${state.analytics.abTestVariantAScore}) vs Variant B (${state.analytics.abTestVariantBScore}) · Est. ROI: ${state.analytics.estimatedRoiMultiplier}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = state.analytics.audienceInsightSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Automated Quality Assurance & Fast Preview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { manager.runQualityAssuranceAudit() },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Run QA Audit", maxLines = 1)
                        }
                    }

                    state.qaChecks.forEach { check ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Passed",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${check.title}: ${check.detail}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Bottom Action Bar: Apply Synthesized Production Settings to Video Studio
            Button(
                onClick = {
                    val synthesizedPrompt = manager.buildSynthesizedPrompt(currentPrompt)
                    val synthesizedNeg = manager.buildSynthesizedNegativePrompt()
                    val targetAspect = state.distribution.selectedPlatform.aspectRatio
                    val targetRes = if (state.techSpecs.resolution.startsWith("720")) "720p" else "1080p"
                    val primaryCamera = state.storyboardNodes.firstOrNull()?.cameraMotion ?: "Zoom In"
                    onApplyPromptAndSettings(
                        synthesizedPrompt,
                        synthesizedNeg,
                        targetAspect,
                        targetRes,
                        state.specializedCategory.defaultDurationSec,
                        primaryCamera
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("apply_video_production_suite_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply Production Spec & Prompt to Video Generator", maxLines = 1)
            }
        }
    }
}
