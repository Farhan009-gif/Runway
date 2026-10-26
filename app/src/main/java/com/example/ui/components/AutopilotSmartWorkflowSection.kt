package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.data.agent.AutopilotSmartWorkflowManager
import com.example.data.agent.CreativeIntent
import com.example.data.agent.SkillProficiencyLevel
import com.example.data.agent.TaskPriority
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutopilotSmartWorkflowSection(
    activeCommandText: String,
    activeProviderName: String,
    enabledSkillsCount: Int,
    enabledMcpToolsCount: Int,
    onDispatchAutomationCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val workflowManager = remember { AutopilotSmartWorkflowManager() }
    val state by workflowManager.uiState.collectAsStateWithLifecycle()

    var selectedPillarTab by rememberSaveable { mutableIntStateOf(0) }

    // Custom Rule Builder inputs
    var newRuleName by rememberSaveable { mutableStateOf("") }
    var newRuleCondition by rememberSaveable { mutableStateOf("WHEN Workflow.Stage == Export") }
    var newRuleAction by rememberSaveable { mutableStateOf("Run pre-export Quality Control audit") }

    // New Scheduled Task inputs
    var newTaskTitle by rememberSaveable { mutableStateOf("") }
    var newTaskBatchGroup by rememberSaveable { mutableStateOf("Creative-Render-Batch") }
    var newTaskPriority by rememberSaveable { mutableStateOf(TaskPriority.HIGH) }

    // Feedback Loop inputs
    var selectedStars by rememberSaveable { mutableIntStateOf(5) }
    var feedbackComment by rememberSaveable { mutableStateOf("") }
    var batterySliderValue by rememberSaveable { mutableFloatStateOf(86f) }

    // Context-aware automatic analysis when command or provider context shifts
    LaunchedEffect(activeCommandText, activeProviderName, enabledSkillsCount, enabledMcpToolsCount) {
        if (activeCommandText.isNotBlank()) {
            workflowManager.analyzeContextAndRecommend(
                activeCommandText = activeCommandText,
                activeProviderName = activeProviderName,
                enabledSkillsCount = enabledSkillsCount,
                enabledMcpToolsCount = enabledMcpToolsCount
            )
        }
    }

    MinimalCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("autopilot_smart_workflow_section")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Top Header & User Control Bar (Preview Mode + Manual Override)
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
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Smart Workflow Automation",
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Smart Workflow Automation",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Interactive User Controls: Preview Mode & Manual Override
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Switch(
                            checked = state.previewModeEnabled,
                            onCheckedChange = workflowManager::togglePreviewMode,
                            modifier = Modifier.testTag("autopilot_preview_mode_switch")
                        )
                        Column {
                            Text(
                                text = "Preview Mode",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (state.previewModeEnabled) "Confirm before run" else "Direct execution",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Switch(
                            checked = state.manualOverridePaused,
                            onCheckedChange = workflowManager::toggleManualOverridePause,
                            modifier = Modifier.testTag("autopilot_manual_override_switch")
                        )
                        Column {
                            Text(
                                text = "Manual Override",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (state.manualOverridePaused) "Paused (User Lock)" else "Automation Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.manualOverridePaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Preview Confirmation Banner (when Preview Mode intercepts an action)
            state.previewSummaryBanner?.let { previewMsg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = previewMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    workflowManager.confirmPreviewedAction(onDispatchAutomationCommand)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("autopilot_confirm_preview_button")
                            ) {
                                Text("Execute Previewed Action", maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Active Automation Workflow Step-by-Step Progress Tracker (Pending / In-Progress / Completed)
            val trackedTemplate = state.templates.find { it.id == state.activeTemplateId }
                ?: state.templates.firstOrNull()

            trackedTemplate?.let { tpl ->
                val trackerSteps = remember(tpl.stages) {
                    tpl.stages.map { stage ->
                        val stepState = when {
                            stage.isCompleted -> WorkflowStepState.COMPLETED
                            stage.isInProgress -> WorkflowStepState.IN_PROGRESS
                            else -> WorkflowStepState.PENDING
                        }
                        AutomationWorkflowStepItem(
                            id = "${tpl.id}_stage_${stage.stageIndex}",
                            stepNumber = stage.stageIndex + 1,
                            title = stage.title,
                            subtitle = "${stage.explanation} (${stage.customizableParameterLabel}: ${stage.parameterValue})",
                            toolOrModuleTag = tpl.category.label,
                            state = stepState,
                            actionCommand = stage.defaultActionCommand
                        )
                    }
                }

                AutomationWorkflowProgressTracker(
                    workflowTitle = "Active Workflow: ${tpl.title}",
                    workflowSubtitle = tpl.subtitle,
                    steps = trackerSteps,
                    onStartOrAdvanceStep = { stepItem ->
                        workflowManager.startOrCompleteTemplateStage(
                            templateId = tpl.id,
                            stageIndex = stepItem.stepNumber - 1,
                            onDispatchCommand = onDispatchAutomationCommand
                        )
                    },
                    onStepClick = {
                        selectedPillarTab = 1 // Jump to Templates & Guide parameter customization
                    },
                    onAdvanceNextActiveStep = {
                        workflowManager.advanceNextActiveStage(
                            templateId = tpl.id,
                            onDispatchCommand = onDispatchAutomationCommand
                        )
                    },
                    onResetWorkflow = {
                        workflowManager.resetTemplateStages(tpl.id)
                    }
                )
            }

            // 5 Pillar Navigation Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val pillarLabels = listOf(
                    "1. Context & Intent",
                    "2. Templates & Guide",
                    "3. Smart Scheduler",
                    "4. Rules & QC",
                    "5. Learning & Metrics"
                )
                pillarLabels.forEachIndexed { idx, label ->
                    FilterChip(
                        selected = selectedPillarTab == idx,
                        onClick = { selectedPillarTab = idx },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("autopilot_pillar_tab_$idx")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            when (selectedPillarTab) {
                // ============================================================
                // PILLAR 0: CONTEXT-AWARE RECOMMENDATIONS & SMART SHORTCUTS
                // ============================================================
                0 -> {
                    Text(
                        text = "Detected Creative Intent: ${state.detectedIntent.displayName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.detectedIntent.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CreativeIntent.entries.forEach { intent ->
                            FilterChip(
                                selected = state.detectedIntent == intent,
                                onClick = {
                                    workflowManager.selectIntentManually(intent, activeProviderName)
                                },
                                label = { Text(intent.displayName, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Context-Aware Recommendations",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    state.recommendations.forEach { rec ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = rec.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "${rec.confidencePercent}% match · -${rec.estimatedTimeSavedSec}s",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            maxLines = 1
                                        )
                                    }
                                }
                                Text(
                                    text = rec.rationale,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = {
                                        workflowManager.applyRecommendation(rec.id, onDispatchAutomationCommand)
                                    },
                                    enabled = !state.manualOverridePaused,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("autopilot_apply_rec_${rec.id.take(8)}")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (rec.isApplied) "Re-Run Recommendation" else "Apply Recommendation",
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "Adaptive Smart Shortcuts (Learned from Usage)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.smartShortcuts.forEach { sc ->
                            OutlinedButton(
                                onClick = { onDispatchAutomationCommand(sc.command) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text("${sc.label} (${sc.keyCombo})", maxLines = 1)
                            }
                        }
                    }
                }

                // ============================================================
                // PILLAR 1: TEMPLATE-BASED AUTOMATION & PROGRESSIVE ASSISTANCE
                // ============================================================
                1 -> {
                    Text(
                        text = "Pre-Defined Automation Templates",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.templates.forEach { tpl ->
                            FilterChip(
                                selected = state.activeTemplateId == tpl.id,
                                onClick = { workflowManager.selectTemplate(tpl.id) },
                                label = { Text("${tpl.category.label} (-${tpl.estimatedMinutesSaved}m)", maxLines = 1) },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("autopilot_template_${tpl.id}")
                            )
                        }
                    }

                    val activeTemplate = state.templates.find { it.id == state.activeTemplateId }
                        ?: state.templates.firstOrNull()

                    activeTemplate?.let { tpl ->
                        val completedCount = tpl.stages.count { it.isCompleted }
                        val progressFraction = if (tpl.stages.isEmpty()) 0f else completedCount.toFloat() / tpl.stages.size

                        Text(
                            text = "${tpl.title} • Progressive Step-by-Step Guidance",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = tpl.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )

                        tpl.stages.forEach { stage ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = stage.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (stage.isCompleted) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Stage Completed",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Text(
                                        text = stage.explanation,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = stage.parameterValue,
                                        onValueChange = { newVal ->
                                            workflowManager.updateStageParameter(tpl.id, stage.stageIndex, newVal)
                                        },
                                        label = { Text("Customize ${stage.customizableParameterLabel}") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            workflowManager.executeSingleTemplateStage(
                                                templateId = tpl.id,
                                                stageIndex = stage.stageIndex,
                                                onDispatchCommand = onDispatchAutomationCommand
                                            )
                                        },
                                        enabled = !state.manualOverridePaused,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .testTag("autopilot_run_stage_${stage.stageIndex}")
                                    ) {
                                        Text(
                                            text = if (stage.isCompleted) "Re-Run Stage ${stage.stageIndex + 1}" else "Run Stage ${stage.stageIndex + 1} Only (Incremental)",
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ============================================================
                // PILLAR 2: INTELLIGENT TASK SCHEDULING & ADAPTIVE TIMING
                // ============================================================
                2 -> {
                    Text(
                        text = "Adaptive Device & Resource Optimization",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BatteryChargingFull,
                                    contentDescription = "Device Power Status",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Mode: ${state.deviceResources.optimalExecutionMode}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Text(
                                text = "Simulated Battery Level: ${batterySliderValue.toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = batterySliderValue,
                                onValueChange = {
                                    batterySliderValue = it
                                    workflowManager.updateDeviceResourceState(
                                        isCharging = state.deviceResources.isCharging,
                                        batteryPercent = it.toInt(),
                                        isIdle = state.deviceResources.isDeviceIdle
                                    )
                                },
                                valueRange = 10f..100f
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = state.deviceResources.isCharging,
                                    onClick = {
                                        workflowManager.updateDeviceResourceState(
                                            isCharging = !state.deviceResources.isCharging,
                                            batteryPercent = batterySliderValue.toInt(),
                                            isIdle = state.deviceResources.isDeviceIdle
                                        )
                                    },
                                    label = { Text(if (state.deviceResources.isCharging) "Charging: ON" else "Unplugged") },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                                FilterChip(
                                    selected = state.deviceResources.isDeviceIdle,
                                    onClick = {
                                        workflowManager.updateDeviceResourceState(
                                            isCharging = state.deviceResources.isCharging,
                                            batteryPercent = batterySliderValue.toInt(),
                                            isIdle = !state.deviceResources.isDeviceIdle
                                        )
                                    },
                                    label = { Text(if (state.deviceResources.isDeviceIdle) "Idle Window: ON" else "Active Use") },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "Priority & Batch Task Queue (${state.scheduledTasks.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    state.scheduledTasks.forEach { task ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(10.dp),
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
                                        text = task.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "${task.priority.label} · ${task.status.name}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = "Batch: ${task.batchGroupKey} • ${task.diagnosticNote}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LinearProgressIndicator(
                                    progress = { task.progressPercent / 100f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newTaskTitle,
                            onValueChange = { newTaskTitle = it },
                            label = { Text("New Scheduled Task") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = {
                                if (newTaskTitle.isNotBlank()) {
                                    workflowManager.enqueueTask(
                                        title = newTaskTitle,
                                        batchGroupKey = newTaskBatchGroup,
                                        priority = newTaskPriority,
                                        commandToExecute = newTaskTitle,
                                        requiresHeavyCompute = false
                                    )
                                    newTaskTitle = ""
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text("+ Queue")
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                workflowManager.runSmartBatchSchedule(onDispatchAutomationCommand)
                            }
                        },
                        enabled = !state.manualOverridePaused,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("autopilot_run_batch_schedule_button")
                    ) {
                        Icon(Icons.Default.Schedule, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Execute Priority & Batch Queue Now", maxLines = 1)
                    }
                }

                // ============================================================
                // PILLAR 3: CUSTOM RULES, QUALITY CONTROL & ASSET MANAGEMENT
                // ============================================================
                3 -> {
                    Text(
                        text = "Automated Quality Control & Asset Tagging",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                workflowManager.runQualityControlAndAssetOrganization()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("autopilot_run_qc_button")
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run Quality Control & Auto-Organize Assets", maxLines = 1)
                    }

                    state.qualityControlChecks.forEach { qc ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Passed",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = qc.checkName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = qc.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (state.organizedAssetTags.isNotEmpty()) {
                        Text(
                            text = "Auto-Organized Asset Categories & Semantic Tags",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        state.organizedAssetTags.forEach { (group, tags) ->
                            Text(
                                text = "$group: ${tags.joinToString(" ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "User-Defined Automation Rules (${state.customRules.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    state.customRules.forEach { rule ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = rule.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${rule.triggerCondition} → ${rule.actionCommand}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = rule.isEnabled,
                                    onCheckedChange = { workflowManager.toggleCustomRule(rule.id) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = newRuleName,
                        onValueChange = { newRuleName = it },
                        label = { Text("Custom Rule Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRuleCondition,
                        onValueChange = { newRuleCondition = it },
                        label = { Text("Trigger Condition (IF/WHEN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newRuleAction,
                        onValueChange = { newRuleAction = it },
                        label = { Text("Automated Action (THEN)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            workflowManager.addCustomRule(newRuleName, newRuleCondition, newRuleAction)
                            newRuleName = ""
                        },
                        enabled = newRuleName.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("autopilot_add_custom_rule_button")
                    ) {
                        Icon(Icons.Default.Rule, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save Custom Automation Rule", maxLines = 1)
                    }
                }

                // ============================================================
                // PILLAR 4: LEARNING, PROFICIENCY & FEEDBACK LOOP METRICS
                // ============================================================
                4 -> {
                    Text(
                        text = "Adaptive Skill Proficiency Level",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = state.proficiencyLevel.guidanceStyle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SkillProficiencyLevel.entries.forEach { lvl ->
                            FilterChip(
                                selected = state.proficiencyLevel == lvl,
                                onClick = { workflowManager.setSkillProficiency(lvl) },
                                label = { Text(lvl.label, maxLines = 1) },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    Text(
                        text = "Success Indicators & Efficiency Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricStatCard(
                            label = "Adoption Rate",
                            value = "${state.metrics.adoptionRatePercent}%",
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCard(
                            label = "Time Saved",
                            value = "${state.metrics.totalMinutesSaved} min",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricStatCard(
                            label = "Error Reduction",
                            value = "${state.metrics.errorReductionPercent}%",
                            modifier = Modifier.weight(1f)
                        )
                        MetricStatCard(
                            label = "Satisfaction",
                            value = "${state.metrics.averageUserRating}★ (${state.metrics.ratingsCount})",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = "Failure Analysis & Root-Cause Diagnostics",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    state.metrics.recentFailureDiagnoses.forEach { diag ->
                        Text(
                            text = "• $diag",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    Text(
                        text = "Rate Autopilot Recommendations (Continuous Improvement)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            FilterChip(
                                selected = selectedStars >= star,
                                onClick = { selectedStars = star },
                                label = { Text("${star}★") },
                                modifier = Modifier.heightIn(min = 48.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = feedbackComment,
                        onValueChange = { feedbackComment = it },
                        label = { Text("Optional feedback to refine suggestions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            workflowManager.submitUserRating(selectedStars, feedbackComment)
                            feedbackComment = ""
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("autopilot_submit_feedback_button")
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Submit Rating & Refine Adaptive Model", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
