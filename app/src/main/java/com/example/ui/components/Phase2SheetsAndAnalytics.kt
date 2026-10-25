package com.example.ui.components

import com.example.ui.theme.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.example.data.provider.LatencyBenchmarkSample
import com.example.data.provider.ProviderSpeedRank
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.local.ModelDescriptorEntity
import com.example.data.local.PromptTemplateEntity
import com.example.data.local.ProviderInstanceEntity
import com.example.data.local.ProviderPresetType
import com.example.data.local.UsageRecordEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricIndigo
import com.example.ui.theme.EmeraldConnected
import com.example.ui.viewmodel.CompareTargetModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareTargetsBottomSheet(
    providers: List<ProviderInstanceEntity>,
    models: List<ModelDescriptorEntity>,
    selectedTargets: List<CompareTargetModel>,
    onToggleTarget: (ModelDescriptorEntity, ProviderInstanceEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val enabledProviders = remember(providers) {
        providers.filter { it.isEnabled }.sortedWith(
            compareBy<ProviderInstanceEntity> { it.presetEnum() != ProviderPresetType.OMNIROUTE }
                .thenBy { it.createdAt }
        )
    }

    val selectedKeys = remember(selectedTargets) {
        selectedTargets.map { it.key }.toSet()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("compare_targets_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Compare Mode Models (2–3)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Selected: ${selectedTargets.size} of 3 • Prompts run side-by-side concurrently",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberCyan
                    )
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("compare_sheet_done_button")
                ) {
                    Text("Done")
                }
            }

            // Active Compare Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedTargets.forEachIndexed { idx, target ->
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, CyberCyan)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            BadgePill(text = "#${idx + 1} ${target.providerBadge}", color = CyberCyan)
                            Text(
                                text = target.displayName,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter models across OmniRoute, Anthropic, Gemini, OpenAI…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                enabledProviders.forEach { provider ->
                    val q = searchQuery.trim().lowercase()
                    val provModels = models.filter { m ->
                        m.providerInstanceId == provider.id &&
                            (q.isEmpty() ||
                                m.displayName.lowercase().contains(q) ||
                                m.modelId.lowercase().contains(q) ||
                                provider.displayName.lowercase().contains(q))
                    }
                    if (provModels.isNotEmpty()) {
                        item(key = "cmp_header_${provider.id}") {
                            Row(
                                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                BadgePill(
                                    text = provider.presetEnum().badgeShort,
                                    color = if (provider.presetEnum() == ProviderPresetType.OMNIROUTE) CyberCyan else ElectricIndigo
                                )
                                Text(
                                    text = provider.displayName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }

                        items(provModels, key = { "cmp_${it.id}" }) { model ->
                            val isSelected = "${model.providerInstanceId}:${model.modelId}" in selectedKeys
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) CyberCyan else MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggleTarget(model, provider) }
                                    .testTag("compare_model_option_${model.modelId}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.displayName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = model.modelId,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                                        contentDescription = if (isSelected) "Selected" else "Not selected",
                                        tint = if (isSelected) EmeraldConnected else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryBottomSheet(
    templates: List<PromptTemplateEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onApplyTemplate: (PromptTemplateEntity) -> Unit,
    onToggleFavorite: (PromptTemplateEntity) -> Unit,
    onSaveTemplate: (
        existingId: String?,
        title: String,
        category: String,
        promptText: String,
        systemPromptOverride: String
    ) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by rememberSaveable { mutableStateOf("All") }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    val categories = listOf("All", "Favorites", "Coding", "Architecture", "Vision", "Custom")

    val filtered = remember(templates, searchQuery, selectedCategory) {
        val q = searchQuery.trim().lowercase()
        templates.filter { tpl ->
            val matchesCat = when (selectedCategory) {
                "All" -> true
                "Favorites" -> tpl.isFavorite
                else -> tpl.category.equals(selectedCategory, ignoreCase = true)
            }
            val matchesQuery = q.isEmpty() ||
                tpl.title.lowercase().contains(q) ||
                tpl.promptText.lowercase().contains(q)
            matchesCat && matchesQuery
        }
    }

    if (showCreateDialog) {
        var title by rememberSaveable { mutableStateOf("") }
        var category by rememberSaveable { mutableStateOf("Coding") }
        var promptText by rememberSaveable { mutableStateOf("") }
        var sysOverride by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Prompt Template") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prompt_dialog_title_input")
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category (Coding, Architecture, Vision, Custom)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text("Prompt Content") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("prompt_dialog_content_input")
                    )
                    OutlinedTextField(
                        value = sysOverride,
                        onValueChange = { sysOverride = it },
                        label = { Text("Optional System Prompt Override") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveTemplate(null, title, category, promptText, sysOverride)
                        showCreateDialog = false
                    },
                    enabled = title.isNotBlank() && promptText.isNotBlank(),
                    modifier = Modifier.testTag("prompt_dialog_save_button")
                ) {
                    Text("Save Template")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.testTag("prompt_library_bottom_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Prompt Library",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Tap any prompt to insert into Chat or Compare Mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("new_prompt_template_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Prompt")
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search saved prompts…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("prompt_library_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered, key = { it.id }) { tpl ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onApplyTemplate(tpl) }
                            .testTag("prompt_template_card_${tpl.id}")
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    BadgePill(text = tpl.category.uppercase(), color = CyberCyan)
                                    Text(
                                        text = tpl.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { onToggleFavorite(tpl) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (tpl.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                                            contentDescription = "Favorite prompt",
                                            tint = if (tpl.isFavorite) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteTemplate(tpl.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete prompt",
                                            tint = CoralError,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = tpl.promptText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (tpl.systemPromptOverride.isNotBlank()) {
                                Text(
                                    text = "System Role: ${tpl.systemPromptOverride}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldConnected,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun UsageAnalyticsScreen(
    usageRecords: List<UsageRecordEntity>,
    providers: List<ProviderInstanceEntity>,
    latencySamples: List<LatencyBenchmarkSample> = emptyList(),
    fastestProvider: ProviderSpeedRank? = null,
    speedRanks: List<ProviderSpeedRank> = emptyList(),
    isProbingLatency: Boolean = false,
    autoProbeIntervalSeconds: Int = 0,
    spendCurrency: String = "INR",
    usdToInrRate: Double = 84.0,
    onRunProbe: () -> Unit = {},
    onSetAutoProbeInterval: (Int) -> Unit = {},
    onClearLatencyHistory: () -> Unit = {},
    onSelectFastestModel: (providerId: String, modelId: String) -> Unit = { _, _ -> },
    onClearUsage: () -> Unit,
    onOpenPromptLibrary: () -> Unit,
    onSetSpendCurrency: (String) -> Unit = {}
) {
    val totalRequests = usageRecords.size
    val totalTokens = usageRecords.sumOf { it.promptTokens + it.completionTokens }
    val totalCostUsd = usageRecords.sumOf { it.estimatedCostUsd }

    val formattedCost = remember(totalCostUsd, spendCurrency, usdToInrRate) {
        if (spendCurrency == "INR") {
            val inr = totalCostUsd * usdToInrRate
            val str = if (inr >= 100.0) String.format(Locale.US, "%.2f", inr)
            else if (inr >= 1.0) String.format(Locale.US, "%.3f", inr)
            else String.format(Locale.US, "%.4f", inr)
            "₹$str"
        } else {
            "$${String.format(Locale.US, "%.4f", totalCostUsd)}"
        }
    }

    val chartPoints = remember(usageRecords, latencySamples) {
        when {
            usageRecords.size >= 2 -> usageRecords.take(20).reversed().map {
                (it.promptTokens + it.completionTokens).toFloat().coerceAtLeast(10f)
            }
            latencySamples.size >= 2 -> latencySamples.takeLast(20).map {
                it.latencyMs.toFloat().coerceAtLeast(10f)
            }
            else -> listOf(120f, 180f, 145f, 230f, 195f, 310f, 260f, 290f)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxSize()
            .testTag("usage_analytics_screen")
    ) {
        // Header row with Currency toggle & Probe / Reset actions
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Usage",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MinimalChip(
                        label = if (spendCurrency == "INR") "₹ INR" else "$ USD",
                        selected = true,
                        onClick = {
                            onSetSpendCurrency(if (spendCurrency == "INR") "USD" else "INR")
                        },
                        testTag = "spend_currency_switcher_card"
                    )
                    MinimalChip(
                        label = if (isProbingLatency) "Probing..." else "Probe",
                        selected = isProbingLatency,
                        onClick = onRunProbe,
                        icon = LucideIcons.Activity,
                        testTag = "run_latency_probe_button"
                    )
                    if (usageRecords.isNotEmpty()) {
                        MinimalChip(
                            label = "Reset",
                            selected = false,
                            onClick = onClearUsage,
                            testTag = "clear_usage_button"
                        )
                    }
                }
            }
        }

        // 3 Simple Stat Cards (Requests, Tokens, Cost)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                UsageKpiCard(
                    label = "Requests",
                    value = totalRequests.toString(),
                    modifier = Modifier.weight(1f)
                )
                UsageKpiCard(
                    label = "Tokens",
                    value = totalTokens.toString(),
                    modifier = Modifier.weight(1f)
                )
                UsageKpiCard(
                    label = "Cost",
                    value = formattedCost,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 1 Minimal Line Chart
        item {
            MinimalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("fastest_provider_hero_card")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (usageRecords.size >= 2) "Token activity" else "Response latency (ms)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = fastestProvider?.let {
                                    "Fastest: ${it.providerName} · ${it.latestLatencyMs}ms"
                                } ?: "Real-time telemetry across active providers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (fastestProvider != null) {
                            MinimalChip(
                                label = "Use fastest",
                                selected = false,
                                onClick = {
                                    onSelectFastestModel(fastestProvider.providerId, fastestProvider.modelId)
                                },
                                testTag = "switch_to_fastest_model_button"
                            )
                        }
                    }

                    val gridColor = MaterialTheme.colorScheme.outline
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(vertical = 8.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val maxVal = (chartPoints.maxOrNull() ?: 1f).coerceAtLeast(1f)
                        val minVal = (chartPoints.minOrNull() ?: 0f).coerceAtMost(maxVal - 1f)
                        val range = (maxVal - minVal).coerceAtLeast(1f)

                        // Subtle horizontal baseline grid
                        listOf(0.2f, 0.5f, 0.85f).forEach { frac ->
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, h * frac),
                                end = Offset(w, h * frac),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        val stepX = if (chartPoints.size > 1) w / (chartPoints.size - 1) else w
                        val coords = chartPoints.mapIndexed { idx, value ->
                            val norm = (value - minVal) / range
                            val x = idx * stepX
                            val y = h - (norm * (h * 0.75f)) - (h * 0.1f)
                            Offset(x, y)
                        }

                        if (coords.size >= 2) {
                            val linePath = androidx.compose.ui.graphics.Path().apply {
                                moveTo(coords.first().x, coords.first().y)
                                for (i in 1 until coords.size) {
                                    val prev = coords[i - 1]
                                    val curr = coords[i]
                                    val midX = (prev.x + curr.x) / 2f
                                    cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                                }
                            }

                            val fillPath = androidx.compose.ui.graphics.Path().apply {
                                addPath(linePath)
                                lineTo(coords.last().x, h)
                                lineTo(coords.first().x, h)
                                close()
                            }

                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        SoftIndigo.copy(alpha = 0.22f),
                                        SoftIndigo.copy(alpha = 0.0f)
                                    )
                                )
                            )

                            drawPath(
                                path = linePath,
                                color = SoftIndigo,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )

                            coords.forEach { pt ->
                                drawCircle(
                                    color = SoftIndigo,
                                    radius = 3.dp.toPx(),
                                    center = pt
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageKpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    MinimalCard(
        modifier = modifier,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

