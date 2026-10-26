package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.image.ImageArtStylePreset
import com.example.data.image.ImageCreationModality
import com.example.data.image.ImagePlatformExportPreset
import com.example.data.image.ImageProductionCapabilitiesManager
import com.example.data.image.Img2ImgControlMode
import com.example.data.image.SpecializedImageCategory
import com.example.data.local.GeneratedImageEntity
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageProductionSuiteSection(
    currentPrompt: String,
    attachedSourceImageUri: String?,
    currentImg2ImgStrength: Float,
    recentGeneratedImages: List<GeneratedImageEntity>,
    onPickReferenceImageFromDevice: () -> Unit,
    onAttachSourceImageUri: (String?) -> Unit,
    onUpdateImg2ImgStrength: (Float) -> Unit,
    onApplyPromptAndSettings: (
        synthesizedPrompt: String,
        negativePrompt: String,
        aspectRatio: String,
        sizeLabel: String,
        stylePreset: String,
        img2ImgStrength: Float
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val manager = remember { ImageProductionCapabilitiesManager() }
    val state by manager.state.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Post-processing & Collaboration inputs
    var objectRemovalInput by rememberSaveable { mutableStateOf("") }
    var bgSwapInput by rememberSaveable { mutableStateOf("Sculpted travertine stone studio cyclorama with soft rim light") }
    var pinCoordinatesInput by rememberSaveable { mutableStateOf("(X:50%, Y:40%)") }
    var pinCommentInput by rememberSaveable { mutableStateOf("") }

    MinimalCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("image_production_suite_section")
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
                        imageVector = Icons.Default.Palette,
                        contentDescription = "AI Image & Img2Img Production Suite",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "AI Image Generation & Img2Img Studio Suite",
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

            // Dedicated Image-to-Image (img2img) Quick Control Card (Always Accessible)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("img2img_workbench_card")
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Image-to-Image (img2img) Reference & Strength",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (attachedSourceImageUri != null) {
                                    "Reference active • Strength ${(currentImg2ImgStrength * 100).toInt()}% (${state.img2ImgControlMode.label})"
                                } else {
                                    "Attach a photo or select a generated image below to guide structure, pose, or style"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (attachedSourceImageUri != null) {
                                IconButton(
                                    onClick = { onAttachSourceImageUri(null) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("clear_img2img_reference_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear reference image"
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onPickReferenceImageFromDevice,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("suite_pick_img2img_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (attachedSourceImageUri == null) "Attach Image" else "Change",
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    // Live Reference Thumbnail Preview if attached
                    if (!attachedSourceImageUri.isNullOrBlank()) {
                        val refModel = remember(attachedSourceImageUri) {
                            if (attachedSourceImageUri.startsWith("/")) File(attachedSourceImageUri) else attachedSourceImageUri
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                            ) {
                                AsyncImage(
                                    model = refModel,
                                    contentDescription = "Img2Img Reference Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Reference Image Locked for Img2Img",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = attachedSourceImageUri.substringAfterLast("/"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else if (recentGeneratedImages.isNotEmpty()) {
                        // Quick strip to use any recent generated image as an img2img seed
                        Text(
                            text = "Or use a recent workspace image as img2img reference:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            recentGeneratedImages.take(6).forEach { genImg ->
                                val thumbModel = remember(genImg.imageUri) {
                                    if (genImg.imageUri.startsWith("/")) File(genImg.imageUri) else genImg.imageUri
                                }
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            onAttachSourceImageUri(genImg.imageUri)
                                            manager.selectModality(ImageCreationModality.IMAGE_TO_IMAGE)
                                        }
                                        .testTag("img2img_quick_ref_${genImg.id.take(6)}")
                                ) {
                                    AsyncImage(
                                        model = thumbModel,
                                        contentDescription = "Use as img2img reference",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }

                    // Denoising / Transformation Strength Slider
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Img2Img Transformation Strength (Denoise)",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = String.format(Locale.US, "%.2f (%d%%)", currentImg2ImgStrength, (currentImg2ImgStrength * 100).toInt()),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = currentImg2ImgStrength.coerceIn(0.05f, 0.95f),
                            onValueChange = { newVal ->
                                manager.updateImg2ImgStrength(newVal)
                                onUpdateImg2ImgStrength(newVal)
                            },
                            valueRange = 0.05f..0.95f,
                            modifier = Modifier.testTag("img2img_strength_slider")
                        )
                    }

                    // Img2Img Conditioning Mode Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Img2ImgControlMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.img2ImgControlMode == mode,
                                onClick = {
                                    manager.selectImg2ImgControlMode(mode)
                                    onUpdateImg2ImgStrength(mode.recommendedStrength)
                                },
                                label = { Text(mode.label, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }
                }
            }

            // 6 Tab Chips covering all 12 Image Generation & Post-Processing Capabilities
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    "1. Modes, Styles & Brand",
                    "2. Variations & Alt-Text",
                    "3. Upscale, Inpaint & BG",
                    "4. Optics, Lighting & Zones",
                    "5. Export & Multi-Platform",
                    "6. Collab, Heatmap & QA"
                )
                tabs.forEachIndexed { index, title ->
                    FilterChip(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        label = { Text(title, maxLines = 1) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("image_suite_tab_$index")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            when (selectedTab) {
                // ============================================================
                // TAB 0: 1. Creation Modalities, Style Transfer, 5. Brand Kit & 10. Specialized Types
                // ============================================================
                0 -> {
                    Text(
                        text = "AI Image Creation Modality",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImageCreationModality.entries.forEach { mod ->
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
                        text = "Artistic Style Transfer & Medium Presets",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ImageArtStylePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.stylePreset == preset,
                                onClick = { manager.selectStylePreset(preset) },
                                label = { Text(preset.label, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Specialized Industry Templates & Brand Kit",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SpecializedImageCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = state.specializedCategory == cat,
                                onClick = { manager.selectSpecializedCategory(cat) },
                                label = { Text("${cat.label} (${cat.defaultAspectRatio})", maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Preserve Reference Image Color Harmony",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Brand Kit: ${state.brandKit.brandName} (${state.brandKit.primaryHex} · ${state.brandKit.secondaryHex})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = state.preserveSourceColorPalette,
                            onCheckedChange = manager::togglePreserveSourceColorPalette
                        )
                    }
                }

                // ============================================================
                // TAB 1: 2. Content Creation Tools (Multi-Angle Variations, Composition, Alt-Text)
                // ============================================================
                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Multi-Angle Concept Variations & Auto Alt-Text",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    manager.generateConceptVariations(currentPrompt)
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Generate 4 Angles", maxLines = 1)
                        }
                    }

                    state.conceptVariations.forEach { v ->
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
                                        text = v.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = v.cameraAngle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = v.promptSnippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = v.autoAltTextCaption,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Text(
                        text = "AI Composition & Framing Assistant",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.compositionSuggestions.forEach { sug ->
                        Text(
                            text = "• [${sug.category}] ${sug.recommendation} (${sug.impactBadge})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ============================================================
                // TAB 2: 3. Advanced Image Processing & 11. Technical Specifications (4K/8K, 16-bit)
                // ============================================================
                2 -> {
                    Text(
                        text = "Super-Resolution Upscaling, Bit-Depth & Cloud Batching",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("2x Crisp (2048²)", "4K Ultra-HD (4096×4096)", "8K Print Master (8192²)").forEach { tier ->
                            FilterChip(
                                selected = state.techSpecs.resolutionTier == tier,
                                onClick = {
                                    manager.updateTechSpecs(resolutionTier = tier)
                                    manager.updateProcessingConfig(upscale = tier)
                                },
                                label = { Text(tier, maxLines = 1) },
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
                        listOf("16-bit Display P3 HDR", "16-bit Adobe RGB", "8-bit sRGB Web", "32-bit EXR Linear").forEach { depth ->
                            FilterChip(
                                selected = state.techSpecs.colorBitDepth == depth,
                                onClick = { manager.updateTechSpecs(bitDepth = depth) },
                                label = { Text(depth, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Object Removal, Background Replacement & Color Grading LUT",
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
                            "Neutral Studio Display P3",
                            "Kodak Portra 400 Warm",
                            "Fuji Velvia High Chroma",
                            "Monochrome Silver Gelatin"
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
                        label = { Text("Smart Object Removal / Inpaint Mask (e.g., stray cables, dust, logo)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = bgSwapInput,
                        onValueChange = {
                            bgSwapInput = it
                            manager.updateProcessingConfig(bgSwap = it)
                        },
                        label = { Text("Background Replacement Environment / Studio Backdrop") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ============================================================
                // TAB 3: 4. Interactive Regional Prompts & 9. Camera Optics / Lighting Rigs
                // ============================================================
                3 -> {
                    Text(
                        text = "Virtual Camera Lens, Studio Lighting Rig & Material Physics",
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
                            "24mm f/1.4 Wide Architectural",
                            "50mm f/1.2 Natural Prime",
                            "85mm f/1.4 Prime Portrait",
                            "100mm f/2.8 Macro Detail"
                        ).forEach { lens ->
                            FilterChip(
                                selected = state.opticsConfig.cameraLens == lens,
                                onClick = { manager.updateOpticsConfig(lens = lens) },
                                label = { Text(lens, maxLines = 1) },
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
                        listOf(
                            "Three-Point Softbox + Indigo Rim",
                            "Golden Hour Natural Sunlight",
                            "Dramatic Chiaroscuro Key Light",
                            "Cyberpunk Neon Reflections"
                        ).forEach { light ->
                            FilterChip(
                                selected = state.opticsConfig.lightingSetup == light,
                                onClick = { manager.updateOpticsConfig(lighting = light) },
                                label = { Text(light, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Regional Prompt Zones & Parametric Variables",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Dynamic Tokens: ${state.interactiveConfig.dynamicVariables.entries.joinToString(" · ") { "{{${it.key}}}=${it.value}" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    state.interactiveConfig.regionalZones.forEach { zone ->
                        Text(
                            text = "• ${zone.regionLabel}: ${zone.regionPrompt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ============================================================
                // TAB 4: 6. Multi-Platform Distribution, Format Conversion & IPTC Metadata
                // ============================================================
                4 -> {
                    Text(
                        text = "Multi-Platform Format Conversion & Color Profile Presets",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ImagePlatformExportPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.distribution.selectedPreset == preset,
                                onClick = { manager.selectPlatformExportPreset(preset) },
                                label = { Text("${preset.platformName} (${preset.aspectRatio})", maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    val activePreset = state.distribution.selectedPreset
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
                                text = "${activePreset.platformName}: ${activePreset.pixelDimensions} · ${activePreset.formatLabel} · ${activePreset.colorProfile}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "WCAG / SEO Alt-Text: ${state.distribution.altTextAccessibility}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "IPTC Keywords: ${state.distribution.iptcKeywords.joinToString(" ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // ============================================================
                // TAB 5: 7. Collaboration, 8. Visual Saliency Analytics & 12. Quality Assurance
                // ============================================================
                5 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Approval Status: ${state.approvalStage.label}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${state.revisions.size} revisions · ${state.pinComments.size} canvas pin notes",
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

                    state.pinComments.forEach { c ->
                        Text(
                            text = "• ${c.coordinatesLabel} ${c.author}: \"${c.comment}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pinCoordinatesInput,
                            onValueChange = { pinCoordinatesInput = it },
                            label = { Text("Pin XY") },
                            singleLine = true,
                            modifier = Modifier.width(104.dp)
                        )
                        OutlinedTextField(
                            value = pinCommentInput,
                            onValueChange = { pinCommentInput = it },
                            label = { Text("Add canvas review pin...") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                manager.addPinComment(pinCoordinatesInput, "Art Director", pinCommentInput)
                                pinCommentInput = ""
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("Pin")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Visual Attention Saliency, A/B CTR & Conversion ROI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Saliency Score: ${state.analytics.visualAttentionScore}/100 · A/B CTR: Variant A (${state.analytics.abVariantACtrPercent}%) vs Variant B (${state.analytics.abVariantBCtrPercent}%) · Est. Lift: ${state.analytics.estimatedConversionLift}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = state.analytics.heatmapInsight,
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
                            text = "Automated Image Quality Assurance",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
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

            // Bottom Action Button: Apply Synthesized Prompt, Img2Img Settings & Optics to Image Studio
            Button(
                onClick = {
                    val hasRef = !attachedSourceImageUri.isNullOrBlank()
                    val synthPrompt = manager.buildSynthesizedImagePrompt(currentPrompt, hasRef)
                    val synthNeg = manager.buildSynthesizedNegativePrompt()
                    val preset = state.distribution.selectedPreset
                    onApplyPromptAndSettings(
                        synthPrompt,
                        synthNeg,
                        preset.aspectRatio,
                        preset.sizeLabel,
                        state.stylePreset.styleNameForStudio,
                        state.img2ImgDenoisingStrength
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("apply_image_production_suite_button")
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Apply Production Spec, Optics & Img2Img to Generator", maxLines = 1)
            }
        }
    }
}
