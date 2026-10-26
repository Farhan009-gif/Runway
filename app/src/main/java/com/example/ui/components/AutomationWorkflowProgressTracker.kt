package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Represents the execution state of an individual step in an automation workflow.
 */
enum class WorkflowStepState(val label: String) {
    PENDING("Pending"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed")
}

/**
 * Immutable UI model for a single step in the [AutomationWorkflowProgressTracker].
 */
data class AutomationWorkflowStepItem(
    val id: String,
    val stepNumber: Int,
    val title: String,
    val subtitle: String,
    val toolOrModuleTag: String,
    val state: WorkflowStepState,
    val stepProgressFraction: Float = when (state) {
        WorkflowStepState.COMPLETED -> 1f
        WorkflowStepState.IN_PROGRESS -> 0.55f
        WorkflowStepState.PENDING -> 0f
    },
    val durationOrEtaLabel: String = when (state) {
        WorkflowStepState.COMPLETED -> "Done · 1.2s"
        WorkflowStepState.IN_PROGRESS -> "Running..."
        WorkflowStepState.PENDING -> "Queued"
    },
    val actionCommand: String = ""
)

/**
 * Visualizes active automation workflows using an interactive, step-by-step vertical timeline
 * progress tracker that clearly distinguishes Pending, In-Progress, and Completed tasks.
 */
@Composable
fun AutomationWorkflowProgressTracker(
    workflowTitle: String,
    workflowSubtitle: String,
    steps: List<AutomationWorkflowStepItem>,
    onStartOrAdvanceStep: (AutomationWorkflowStepItem) -> Unit,
    onStepClick: (AutomationWorkflowStepItem) -> Unit = {},
    onAdvanceNextActiveStep: () -> Unit = {},
    onResetWorkflow: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val totalSteps = steps.size.coerceAtLeast(1)
    val completedCount = steps.count { it.state == WorkflowStepState.COMPLETED }
    val inProgressCount = steps.count { it.state == WorkflowStepState.IN_PROGRESS }
    val pendingCount = steps.count { it.state == WorkflowStepState.PENDING }

    val rawOverallProgress = (completedCount.toFloat() + (inProgressCount * 0.5f)) / totalSteps.toFloat()
    val animatedOverallProgress by animateFloatAsState(
        targetValue = rawOverallProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "overall_workflow_progress"
    )
    val overallPercent = (animatedOverallProgress * 100).toInt()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("automation_workflow_progress_tracker")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header: Workflow Title & Overall Completion Percentage Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workflowTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = workflowSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Surface(
                    color = if (completedCount == steps.size && steps.isNotEmpty()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.testTag("workflow_tracker_percent_badge")
                ) {
                    Text(
                        text = "$overallPercent% Complete",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (completedCount == steps.size && steps.isNotEmpty()) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        maxLines = 1
                    )
                }
            }

            // 2. Overall Progress Bar & Status Counters (Completed / In Progress / Pending)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { animatedOverallProgress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag("workflow_tracker_overall_progress_bar")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WorkflowSummaryCounterChip(
                        label = "Completed",
                        count = completedCount,
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workflow_counter_completed")
                    )
                    WorkflowSummaryCounterChip(
                        label = "In Progress",
                        count = inProgressCount,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f),
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workflow_counter_in_progress")
                    )
                    WorkflowSummaryCounterChip(
                        label = "Pending",
                        count = pendingCount,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("workflow_counter_pending")
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // 3. Step-by-Step Vertical Timeline Nodes
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    val isLastStep = index == steps.lastIndex
                    WorkflowTimelineStepRow(
                        step = step,
                        isLastStep = isLastStep,
                        onActionClick = { onStartOrAdvanceStep(step) },
                        onRowClick = { onStepClick(step) }
                    )
                }
            }

            // 4. Footer Quick Controls: Advance Next Step & Reset Tracker
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onResetWorkflow,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("workflow_tracker_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset workflow progress",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset Steps", maxLines = 1)
                }

                Button(
                    onClick = onAdvanceNextActiveStep,
                    enabled = completedCount < steps.size,
                    modifier = Modifier
                        .weight(1.35f)
                        .heightIn(min = 48.dp)
                        .testTag("workflow_tracker_advance_button")
                ) {
                    Icon(
                        imageVector = if (inProgressCount > 0) Icons.Default.SkipNext else Icons.Default.PlayArrow,
                        contentDescription = "Advance workflow step",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            completedCount == steps.size -> "All Steps Completed"
                            inProgressCount > 0 -> "Complete Active Step"
                            else -> "Start Next Step"
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkflowSummaryCounterChip(
    label: String,
    count: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

@Composable
private fun WorkflowTimelineStepRow(
    step: AutomationWorkflowStepItem,
    isLastStep: Boolean,
    onActionClick: () -> Unit,
    onRowClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "in_progress_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val nodeBackgroundColor by animateColorAsState(
        targetValue = when (step.state) {
            WorkflowStepState.COMPLETED -> MaterialTheme.colorScheme.primary
            WorkflowStepState.IN_PROGRESS -> MaterialTheme.colorScheme.tertiaryContainer
            WorkflowStepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "node_bg_color"
    )

    val connectorColor by animateColorAsState(
        targetValue = when (step.state) {
            WorkflowStepState.COMPLETED -> MaterialTheme.colorScheme.primary
            WorkflowStepState.IN_PROGRESS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            WorkflowStepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "connector_line_color"
    )

    val cardContainerColor by animateColorAsState(
        targetValue = when (step.state) {
            WorkflowStepState.IN_PROGRESS -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
            WorkflowStepState.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            WorkflowStepState.PENDING -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        },
        label = "step_card_bg"
    )

    val cardBorderColor = when (step.state) {
        WorkflowStepState.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        WorkflowStepState.COMPLETED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        WorkflowStepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .testTag("workflow_step_row_${step.stepNumber}")
    ) {
        // Left Column: Step Node Indicator + Vertical Connector Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .scale(if (step.state == WorkflowStepState.IN_PROGRESS) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(nodeBackgroundColor)
                    .border(
                        width = if (step.state == WorkflowStepState.PENDING) 1.5.dp else 0.dp,
                        color = if (step.state == WorkflowStepState.PENDING) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape
                    )
                    .semantics {
                        contentDescription = "Step ${step.stepNumber} ${step.state.label}"
                    }
            ) {
                when (step.state) {
                    WorkflowStepState.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    WorkflowStepState.IN_PROGRESS -> {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    WorkflowStepState.PENDING -> {
                        Text(
                            text = step.stepNumber.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!isLastStep) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .background(connectorColor, RoundedCornerShape(2.dp))
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Right Column: Step Details Card
        Surface(
            color = cardContainerColor,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                width = if (step.state == WorkflowStepState.IN_PROGRESS) 1.5.dp else 1.dp,
                color = cardBorderColor
            ),
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLastStep) 0.dp else 12.dp)
                .clickable(onClick = onRowClick)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${step.toolOrModuleTag} • ${step.durationOrEtaLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    WorkflowStateBadge(state = step.state)
                }

                Text(
                    text = step.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (step.state == WorkflowStepState.IN_PROGRESS) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = { step.stepProgressFraction.coerceIn(0.05f, 1f) },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Text(
                            text = "Executing automation step (${(step.stepProgressFraction * 100).toInt()}%)...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onActionClick,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("workflow_step_action_button_${step.stepNumber}")
                    ) {
                        Icon(
                            imageVector = when (step.state) {
                                WorkflowStepState.PENDING -> Icons.Default.PlayArrow
                                WorkflowStepState.IN_PROGRESS -> Icons.Default.Check
                                WorkflowStepState.COMPLETED -> Icons.Default.Refresh
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when (step.state) {
                                WorkflowStepState.PENDING -> "Start Step"
                                WorkflowStepState.IN_PROGRESS -> "Mark Completed"
                                WorkflowStepState.COMPLETED -> "Re-Run Step"
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowStateBadge(state: WorkflowStepState) {
    val (bgColor, textColor) = when (state) {
        WorkflowStepState.COMPLETED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        WorkflowStepState.IN_PROGRESS ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        WorkflowStepState.PENDING ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (state == WorkflowStepState.PENDING) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
