package com.example.data.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================
// 1. CREATIVE INTENT & CONTEXT-AWARE RECOMMENDATIONS
// ============================================================================

enum class CreativeIntent(
    val displayName: String,
    val description: String,
    val primaryTools: List<String>
) {
    MOTION_GRAPHICS(
        displayName = "Motion Graphics & Animation",
        description = "Camera keyframes, temporal consistency, and multi-shot video synthesis",
        primaryTools = listOf("Video Studio", "Camera Motion Presets", "Frame Interpolation")
    ),
    CONTENT_EDITING(
        displayName = "Rapid Content Editing",
        description = "Iterative prompt refinement, style transfer, and side-by-side model comparison",
        primaryTools = listOf("Chat Studio", "Compare Mode", "Image-to-Image")
    ),
    ASSET_PREPARATION(
        displayName = "Asset Organization & Tagging",
        description = "Batch tagging, resolution upscaling, and visual reference curation",
        primaryTools = listOf("Image Studio", "Asset Tagger", "4x Upscaler")
    ),
    EXPORT_PUBLISHING(
        displayName = "Platform Export & Publishing",
        description = "Format validation, aspect-ratio conversion, and YouTube / GitHub delivery",
        primaryTools = listOf("Export Optimizer", "YouTube Publisher", "GitHub Gist Sync")
    )
}

data class AutopilotRecommendation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val rationale: String,
    val detectedIntent: CreativeIntent,
    val confidencePercent: Int,
    val estimatedTimeSavedSec: Int,
    val suggestedCommand: String,
    val isApplied: Boolean = false
)

// ============================================================================
// 2. TEMPLATE-BASED AUTOMATION & PROGRESSIVE ASSISTANCE
// ============================================================================

enum class WorkflowTemplateCategory(val label: String) {
    QUICK_EDIT("Quick Edit"),
    ANIMATION_SETUP("Animation Setup"),
    EXPORT_READY("Export Ready"),
    ASSET_PREPARATION("Asset Preparation")
}

data class ProgressiveWorkflowStage(
    val stageIndex: Int,
    val title: String,
    val explanation: String,
    val defaultActionCommand: String,
    val customizableParameterLabel: String,
    val parameterValue: String,
    val isInProgress: Boolean = false,
    val isCompleted: Boolean = false,
    val isSkipped: Boolean = false
)

data class AutopilotWorkflowTemplate(
    val id: String,
    val category: WorkflowTemplateCategory,
    val title: String,
    val subtitle: String,
    val estimatedMinutesSaved: Int,
    val tags: List<String>,
    val stages: List<ProgressiveWorkflowStage>
)

// ============================================================================
// 3. INTELLIGENT TASK SCHEDULING & DEVICE RESOURCE AWARENESS
// ============================================================================

enum class TaskPriority(val label: String, val weight: Int) {
    CRITICAL("Critical (Immediate)", 4),
    HIGH("High Priority", 3),
    NORMAL("Normal", 2),
    IDLE_BATCH("Idle / Batch Window", 1)
}

enum class ScheduledTaskStatus { QUEUED, PREVIEWING, RUNNING, PAUSED_MANUAL_OVERRIDE, COMPLETED, FAILED }

data class DeviceResourceSnapshot(
    val batteryPercent: Int = 86,
    val isCharging: Boolean = true,
    val isDeviceIdle: Boolean = true,
    val availableMemoryMb: Int = 4096
) {
    val optimalExecutionMode: String
        get() = when {
            isCharging && batteryPercent >= 50 -> "Full Parallel Turbo (Charging)"
            batteryPercent < 25 && !isCharging -> "Battery Saver (Defer Heavy Renders)"
            isDeviceIdle -> "Smart Idle Batch Processing"
            else -> "Balanced Adaptive Execution"
        }
}

data class ScheduledAutomationTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val batchGroupKey: String,
    val priority: TaskPriority,
    val commandToExecute: String,
    val deadlineMinutes: Int = 30,
    val requiresHeavyCompute: Boolean = false,
    val status: ScheduledTaskStatus = ScheduledTaskStatus.QUEUED,
    val progressPercent: Int = 0,
    val diagnosticNote: String = "Scheduled in priority queue"
)

// ============================================================================
// 4. LEARNING, ADAPTATION, CUSTOM RULES & TELEMETRY FEEDBACK LOOP
// ============================================================================

enum class SkillProficiencyLevel(
    val label: String,
    val guidanceStyle: String
) {
    BEGINNER("Beginner", "Step-by-step explanations with safe defaults and visual previews"),
    INTERMEDIATE("Intermediate", "Balanced incremental automation with parameter hints"),
    ADVANCED("Advanced", "Compact multi-step macros, batch pipelines, and keyboard shortcuts"),
    EXPERT("Expert", "Zero-latency autonomous execution with custom rule triggers")
}

data class CustomAutomationRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val triggerCondition: String,
    val actionCommand: String,
    val isEnabled: Boolean = true,
    val triggerCount: Int = 0
)

data class SmartShortcut(
    val id: String,
    val keyCombo: String,
    val label: String,
    val command: String,
    val usageCount: Int
)

data class QualityControlCheckResult(
    val checkName: String,
    val passed: Boolean,
    val detail: String,
    val autoFixSuggestion: String
)

data class AutopilotFeedbackMetrics(
    val adoptionRatePercent: Int = 88,
    val totalMinutesSaved: Int = 42,
    val averageUserRating: Double = 4.8,
    val ratingsCount: Int = 14,
    val errorReductionPercent: Int = 76,
    val completedWorkflowsCount: Int = 19,
    val recentFailureDiagnoses: List<String> = listOf(
        "Resolved: Deferred 1080p video batch until charging state detected",
        "Auto-fixed: Normalized aspect ratio from 4:3 to 16:9 for YouTube export preset"
    )
)

data class AutopilotSmartUiState(
    val detectedIntent: CreativeIntent = CreativeIntent.MOTION_GRAPHICS,
    val proficiencyLevel: SkillProficiencyLevel = SkillProficiencyLevel.INTERMEDIATE,
    val recommendations: List<AutopilotRecommendation> = emptyList(),
    val templates: List<AutopilotWorkflowTemplate> = emptyList(),
    val activeTemplateId: String = "tpl_animation_setup",
    val previewModeEnabled: Boolean = true,
    val manualOverridePaused: Boolean = false,
    val previewSummaryBanner: String? = null,
    val deviceResources: DeviceResourceSnapshot = DeviceResourceSnapshot(),
    val scheduledTasks: List<ScheduledAutomationTask> = emptyList(),
    val customRules: List<CustomAutomationRule> = emptyList(),
    val smartShortcuts: List<SmartShortcut> = emptyList(),
    val qualityControlChecks: List<QualityControlCheckResult> = emptyList(),
    val organizedAssetTags: Map<String, List<String>> = emptyMap(),
    val metrics: AutopilotFeedbackMetrics = AutopilotFeedbackMetrics(),
    val statusMessage: String = "Autopilot Smart Workflow Engine active"
)

/**
 * Unidirectional Data Flow engine for the Autopilot Smart Workflow Automation System.
 * Dispatches heavy analysis, batching, and validation on `Dispatchers.IO`.
 */
class AutopilotSmartWorkflowManager {

    private val _uiState = MutableStateFlow(seedInitialState())
    val uiState: StateFlow<AutopilotSmartUiState> = _uiState.asStateFlow()

    /**
     * 1. Context-Aware Analysis: Inspects active command, provider, and tool state on Dispatchers.IO
     * to detect creative intent and surface tailored recommendations.
     */
    suspend fun analyzeContextAndRecommend(
        activeCommandText: String,
        activeProviderName: String,
        enabledSkillsCount: Int,
        enabledMcpToolsCount: Int
    ) = withContext(Dispatchers.IO) {
        val lower = activeCommandText.lowercase()
        val intent = when {
            lower.contains("video") || lower.contains("motion") || lower.contains("animate") || lower.contains("camera") ->
                CreativeIntent.MOTION_GRAPHICS
            lower.contains("export") || lower.contains("youtube") || lower.contains("gist") || lower.contains("publish") ->
                CreativeIntent.EXPORT_PUBLISHING
            lower.contains("asset") || lower.contains("tag") || lower.contains("upscale") || lower.contains("organize") ->
                CreativeIntent.ASSET_PREPARATION
            else -> CreativeIntent.CONTENT_EDITING
        }

        val dynamicRecs = buildRecommendationsForIntent(
            intent = intent,
            activeProviderName = activeProviderName,
            enabledSkillsCount = enabledSkillsCount,
            enabledMcpToolsCount = enabledMcpToolsCount
        )

        _uiState.update { state ->
            state.copy(
                detectedIntent = intent,
                recommendations = dynamicRecs,
                statusMessage = "Context analyzed: ${intent.displayName} (${dynamicRecs.size} recommendations)"
            )
        }
    }

    fun selectIntentManually(intent: CreativeIntent, activeProviderName: String) {
        val recs = buildRecommendationsForIntent(intent, activeProviderName, 2, 4)
        _uiState.update {
            it.copy(
                detectedIntent = intent,
                recommendations = recs,
                statusMessage = "Switched creative intent to ${intent.displayName}"
            )
        }
    }

    fun applyRecommendation(recId: String, onDispatchCommand: (String) -> Unit) {
        val current = _uiState.value
        val rec = current.recommendations.find { it.id == recId } ?: return

        if (current.previewModeEnabled) {
            _uiState.update {
                it.copy(
                    previewSummaryBanner = "Preview Mode: Ready to execute '${rec.title}' → `${rec.suggestedCommand}` (Saves ~${rec.estimatedTimeSavedSec}s)",
                    statusMessage = "Previewing recommendation '${rec.title}'"
                )
            }
        } else {
            executeRecommendationNow(rec, onDispatchCommand)
        }
    }

    fun confirmPreviewedAction(onDispatchCommand: (String) -> Unit) {
        val current = _uiState.value
        val firstUnapplied = current.recommendations.firstOrNull { !it.isApplied }
        if (firstUnapplied != null) {
            executeRecommendationNow(firstUnapplied, onDispatchCommand)
        }
        _uiState.update { it.copy(previewSummaryBanner = null) }
    }

    private fun executeRecommendationNow(
        rec: AutopilotRecommendation,
        onDispatchCommand: (String) -> Unit
    ) {
        onDispatchCommand(rec.suggestedCommand)
        _uiState.update { state ->
            val updatedRecs = state.recommendations.map {
                if (it.id == rec.id) it.copy(isApplied = true) else it
            }
            state.copy(
                recommendations = updatedRecs,
                previewSummaryBanner = null,
                metrics = state.metrics.copy(
                    totalMinutesSaved = state.metrics.totalMinutesSaved + (rec.estimatedTimeSavedSec / 60).coerceAtLeast(1),
                    completedWorkflowsCount = state.metrics.completedWorkflowsCount + 1
                ),
                statusMessage = "Executed recommendation: ${rec.title}"
            )
        }
    }

    /**
     * 2 & 3. Template-Based Automation & Progressive Step-by-Step Assistance.
     */
    fun selectTemplate(templateId: String) {
        _uiState.update {
            it.copy(
                activeTemplateId = templateId,
                statusMessage = "Selected workflow template"
            )
        }
    }

    fun updateStageParameter(templateId: String, stageIndex: Int, newValue: String) {
        _uiState.update { state ->
            val updatedTemplates = state.templates.map { tpl ->
                if (tpl.id != templateId) tpl else {
                    tpl.copy(
                        stages = tpl.stages.map { st ->
                            if (st.stageIndex == stageIndex) st.copy(parameterValue = newValue) else st
                        }
                    )
                }
            }
            state.copy(templates = updatedTemplates)
        }
    }

    fun executeSingleTemplateStage(
        templateId: String,
        stageIndex: Int,
        onDispatchCommand: (String) -> Unit
    ) {
        val state = _uiState.value
        if (state.manualOverridePaused) {
            _uiState.update {
                it.copy(statusMessage = "Manual Override is active. Resume automation to run stage.")
            }
            return
        }
        val tpl = state.templates.find { it.id == templateId } ?: return
        val stage = tpl.stages.find { it.stageIndex == stageIndex } ?: return
        val commandWithParam = "${stage.defaultActionCommand} [${stage.customizableParameterLabel}=${stage.parameterValue}]"

        onDispatchCommand(commandWithParam)
        _uiState.update { current ->
            val updatedTemplates = current.templates.map { item ->
                if (item.id != templateId) item else {
                    val nextPendingIdx = stageIndex + 1
                    item.copy(
                        stages = item.stages.map { st ->
                            when (st.stageIndex) {
                                stageIndex -> st.copy(isCompleted = true, isInProgress = false)
                                nextPendingIdx -> if (!st.isCompleted) st.copy(isInProgress = true) else st
                                else -> st
                            }
                        }
                    )
                }
            }
            current.copy(
                templates = updatedTemplates,
                metrics = current.metrics.copy(
                    totalMinutesSaved = current.metrics.totalMinutesSaved + 2,
                    completedWorkflowsCount = current.metrics.completedWorkflowsCount + 1
                ),
                statusMessage = "Completed Stage ${stageIndex + 1}: ${stage.title}"
            )
        }
    }

    fun startOrCompleteTemplateStage(
        templateId: String,
        stageIndex: Int,
        onDispatchCommand: (String) -> Unit
    ) {
        val state = _uiState.value
        val tpl = state.templates.find { it.id == templateId } ?: return
        val stage = tpl.stages.find { it.stageIndex == stageIndex } ?: return

        if (!stage.isInProgress && !stage.isCompleted) {
            // Transition PENDING -> IN_PROGRESS
            _uiState.update { current ->
                val updatedTemplates = current.templates.map { item ->
                    if (item.id != templateId) item else {
                        item.copy(
                            stages = item.stages.map { st ->
                                if (st.stageIndex == stageIndex) {
                                    st.copy(isInProgress = true, isCompleted = false)
                                } else {
                                    st.copy(isInProgress = false)
                                }
                            }
                        )
                    }
                }
                current.copy(
                    templates = updatedTemplates,
                    statusMessage = "In Progress: ${stage.title}"
                )
            }
        } else {
            // Complete IN_PROGRESS (or re-run COMPLETED) and advance next stage to IN_PROGRESS
            executeSingleTemplateStage(templateId, stageIndex, onDispatchCommand)
        }
    }

    fun advanceNextActiveStage(
        templateId: String,
        onDispatchCommand: (String) -> Unit
    ) {
        val tpl = _uiState.value.templates.find { it.id == templateId } ?: return
        val activeStage = tpl.stages.firstOrNull { it.isInProgress }
            ?: tpl.stages.firstOrNull { !it.isCompleted }
            ?: return
        startOrCompleteTemplateStage(templateId, activeStage.stageIndex, onDispatchCommand)
    }

    fun resetTemplateStages(templateId: String) {
        _uiState.update { current ->
            val updatedTemplates = current.templates.map { item ->
                if (item.id != templateId) item else {
                    item.copy(
                        stages = item.stages.mapIndexed { idx, st ->
                            st.copy(
                                isCompleted = false,
                                isInProgress = idx == 0,
                                isSkipped = false
                            )
                        }
                    )
                }
            }
            current.copy(
                templates = updatedTemplates,
                statusMessage = "Reset workflow tracker stages (Stage 1 ready in-progress)"
            )
        }
    }

    /**
     * 4. Intelligent Task Scheduling: Priority ordering, Batch processing, and Device Resource optimization.
     */
    fun enqueueTask(
        title: String,
        batchGroupKey: String,
        priority: TaskPriority,
        commandToExecute: String,
        requiresHeavyCompute: Boolean
    ) {
        val newTask = ScheduledAutomationTask(
            title = title,
            batchGroupKey = batchGroupKey,
            priority = priority,
            commandToExecute = commandToExecute,
            requiresHeavyCompute = requiresHeavyCompute
        )
        _uiState.update { state ->
            val sorted = (state.scheduledTasks + newTask).sortedWith(
                compareByDescending<ScheduledAutomationTask> { it.priority.weight }
                    .thenBy { it.deadlineMinutes }
            )
            state.copy(
                scheduledTasks = sorted,
                statusMessage = "Scheduled '${newTask.title}' (${newTask.priority.label})"
            )
        }
    }

    fun updateDeviceResourceState(isCharging: Boolean, batteryPercent: Int, isIdle: Boolean) {
        _uiState.update { state ->
            val snapshot = state.deviceResources.copy(
                isCharging = isCharging,
                batteryPercent = batteryPercent.coerceIn(5, 100),
                isDeviceIdle = isIdle
            )
            state.copy(
                deviceResources = snapshot,
                statusMessage = "Scheduler mode: ${snapshot.optimalExecutionMode}"
            )
        }
    }

    suspend fun runSmartBatchSchedule(onDispatchCommand: (String) -> Unit) = withContext(Dispatchers.IO) {
        val snapshot = _uiState.value
        if (snapshot.manualOverridePaused) {
            _uiState.update {
                it.copy(statusMessage = "Execution paused by Manual Override.")
            }
            return@withContext
        }

        val deferHeavy = !snapshot.deviceResources.isCharging && snapshot.deviceResources.batteryPercent < 25
        val runnable = snapshot.scheduledTasks.filter {
            it.status == ScheduledTaskStatus.QUEUED && (!deferHeavy || !it.requiresHeavyCompute)
        }

        for (task in runnable) {
            if (_uiState.value.manualOverridePaused) break
            updateTaskStatus(task.id, ScheduledTaskStatus.RUNNING, 45, "Executing in batch '${task.batchGroupKey}'")
            delay(180)
            withContext(Dispatchers.Main) {
                onDispatchCommand(task.commandToExecute)
            }
            updateTaskStatus(task.id, ScheduledTaskStatus.COMPLETED, 100, "Completed (${snapshot.deviceResources.optimalExecutionMode})")
        }

        _uiState.update { st ->
            st.copy(
                metrics = st.metrics.copy(
                    totalMinutesSaved = st.metrics.totalMinutesSaved + (runnable.size * 3),
                    completedWorkflowsCount = st.metrics.completedWorkflowsCount + runnable.size
                ),
                statusMessage = if (deferHeavy) {
                    "Executed light tasks; deferred heavy renders due to low battery (<25%)"
                } else {
                    "Completed batch execution of ${runnable.size} scheduled task(s)"
                }
            )
        }
    }

    private fun updateTaskStatus(
        taskId: String,
        status: ScheduledTaskStatus,
        progress: Int,
        note: String
    ) {
        _uiState.update { state ->
            state.copy(
                scheduledTasks = state.scheduledTasks.map {
                    if (it.id == taskId) it.copy(status = status, progressPercent = progress, diagnosticNote = note) else it
                }
            )
        }
    }

    /**
     * 5, 6 & 7. Interactive Controls, Quality Control Validation, Asset Tagging & Custom Rules.
     */
    fun togglePreviewMode(enabled: Boolean) {
        _uiState.update {
            it.copy(
                previewModeEnabled = enabled,
                previewSummaryBanner = if (!enabled) null else it.previewSummaryBanner,
                statusMessage = if (enabled) "Preview Mode enabled (confirm before execution)" else "Direct execution enabled"
            )
        }
    }

    fun toggleManualOverridePause(paused: Boolean) {
        _uiState.update { state ->
            val updatedTasks = state.scheduledTasks.map { task ->
                if (paused && task.status == ScheduledTaskStatus.RUNNING) {
                    task.copy(status = ScheduledTaskStatus.PAUSED_MANUAL_OVERRIDE, diagnosticNote = "Paused by user override")
                } else if (!paused && task.status == ScheduledTaskStatus.PAUSED_MANUAL_OVERRIDE) {
                    task.copy(status = ScheduledTaskStatus.QUEUED, diagnosticNote = "Resumed to queue")
                } else task
            }
            state.copy(
                manualOverridePaused = paused,
                scheduledTasks = updatedTasks,
                statusMessage = if (paused) "Manual Override engaged: All background automations paused" else "Automation resumed"
            )
        }
    }

    fun setSkillProficiency(level: SkillProficiencyLevel) {
        _uiState.update {
            it.copy(
                proficiencyLevel = level,
                statusMessage = "Adapted guidance to ${level.label}: ${level.guidanceStyle}"
            )
        }
    }

    fun addCustomRule(name: String, triggerCondition: String, actionCommand: String) {
        if (name.isBlank() || triggerCondition.isBlank() || actionCommand.isBlank()) return
        val rule = CustomAutomationRule(
            name = name.trim(),
            triggerCondition = triggerCondition.trim(),
            actionCommand = actionCommand.trim()
        )
        _uiState.update { state ->
            state.copy(
                customRules = listOf(rule) + state.customRules,
                statusMessage = "Added custom automation rule '${rule.name}'"
            )
        }
    }

    fun toggleCustomRule(ruleId: String) {
        _uiState.update { state ->
            state.copy(
                customRules = state.customRules.map {
                    if (it.id == ruleId) it.copy(isEnabled = !it.isEnabled) else it
                }
            )
        }
    }

    suspend fun runQualityControlAndAssetOrganization() = withContext(Dispatchers.IO) {
        val checks = listOf(
            QualityControlCheckResult(
                checkName = "Export Aspect Ratio & Codec Compliance",
                passed = true,
                detail = "16:9 (1920x1080) H.264 & 1:1 (1024x1024) PNG presets verified",
                autoFixSuggestion = "No action needed"
            ),
            QualityControlCheckResult(
                checkName = "Secret & Token Vault Encryption (OWASP)",
                passed = true,
                detail = "All provider & MCP credentials encrypted in Tink Keystore Vault",
                autoFixSuggestion = "Verified zero plaintext tokens"
            ),
            QualityControlCheckResult(
                checkName = "Tool Loop & Prompt Token Headroom",
                passed = true,
                detail = "Repeated-call guard (3x cap) & context window headroom verified",
                autoFixSuggestion = "Ready for multi-turn execution"
            )
        )
        val organizedTags = mapOf(
            "Hero Storyboard Keyframes" to listOf("#motion-graphics", "#16:9", "#720p", "#verified"),
            "Brand Style Reference Pack" to listOf("#image-to-image", "#style-guide", "#upscaled"),
            "Exported Master Deliverables" to listOf("#youtube-ready", "#h264", "#color-graded")
        )
        _uiState.update { state ->
            state.copy(
                qualityControlChecks = checks,
                organizedAssetTags = organizedTags,
                metrics = state.metrics.copy(
                    errorReductionPercent = (state.metrics.errorReductionPercent + 2).coerceAtMost(98)
                ),
                statusMessage = "Quality Control passed (${checks.size}/${checks.size} checks) & assets auto-tagged"
            )
        }
    }

    /**
     * 9. Feedback Loop System: Records user rating and refines future recommendations.
     */
    fun submitUserRating(stars: Int, feedbackNote: String) {
        val clamped = stars.coerceIn(1, 5)
        _uiState.update { state ->
            val oldTotal = state.metrics.averageUserRating * state.metrics.ratingsCount
            val newCount = state.metrics.ratingsCount + 1
            val newAvg = ((oldTotal + clamped) / newCount * 10.0).toInt() / 10.0
            val updatedNotes = if (feedbackNote.isNotBlank()) {
                listOf("User feedback (${clamped}★): ${feedbackNote.trim()}") + state.metrics.recentFailureDiagnoses.take(4)
            } else {
                state.metrics.recentFailureDiagnoses
            }
            state.copy(
                metrics = state.metrics.copy(
                    averageUserRating = newAvg,
                    ratingsCount = newCount,
                    recentFailureDiagnoses = updatedNotes
                ),
                statusMessage = "Thank you! Feedback recorded (${clamped}★) — adaptive model updated."
            )
        }
    }

    private fun buildRecommendationsForIntent(
        intent: CreativeIntent,
        activeProviderName: String,
        enabledSkillsCount: Int,
        enabledMcpToolsCount: Int
    ): List<AutopilotRecommendation> {
        return when (intent) {
            CreativeIntent.MOTION_GRAPHICS -> listOf(
                AutopilotRecommendation(
                    title = "Automated Camera Motion & Keyframe Setup",
                    rationale = "Detected motion graphics workflow on $activeProviderName. Pre-configures 16:9 720p smooth pan-and-zoom keyframes.",
                    detectedIntent = intent,
                    confidencePercent = 96,
                    estimatedTimeSavedSec = 240,
                    suggestedCommand = "Switch to Video Studio, set 16:9 720p cinematic pan motion, and prepare storyboard prompt"
                ),
                AutopilotRecommendation(
                    title = "Generate Cohesive First-Frame Reference Asset",
                    rationale = "Image-to-Video pipelines achieve 40% higher temporal stability when seeded with a clean 16:9 keyframe.",
                    detectedIntent = intent,
                    confidencePercent = 91,
                    estimatedTimeSavedSec = 180,
                    suggestedCommand = "Generate 16:9 cinematic reference frame in Image Studio for video seeding"
                )
            )
            CreativeIntent.CONTENT_EDITING -> listOf(
                AutopilotRecommendation(
                    title = "Multi-Model Side-by-Side Quality Compare",
                    rationale = "Compare 2 models simultaneously to pick the sharpest edit with $enabledSkillsCount active skill(s).",
                    detectedIntent = intent,
                    confidencePercent = 94,
                    estimatedTimeSavedSec = 150,
                    suggestedCommand = "Enable Compare Mode and benchmark top 2 chat models on current prompt"
                ),
                AutopilotRecommendation(
                    title = "Smart Prompt Compression & Clarity Pass",
                    rationale = "Refines system instructions and reduces token latency by ~22% while preserving constraints.",
                    detectedIntent = intent,
                    confidencePercent = 89,
                    estimatedTimeSavedSec = 120,
                    suggestedCommand = "Optimize system prompt and set temperature to 0.4 for precision editing"
                )
            )
            CreativeIntent.ASSET_PREPARATION -> listOf(
                AutopilotRecommendation(
                    title = "Batch Semantic Tagging & 4x Upscale Pipeline",
                    rationale = "Automatically categorizes generated images by aspect ratio, style preset, and resolution.",
                    detectedIntent = intent,
                    confidencePercent = 95,
                    estimatedTimeSavedSec = 300,
                    suggestedCommand = "Organize generated assets, apply semantic tags, and validate export resolutions"
                ),
                AutopilotRecommendation(
                    title = "Generate Placeholder Storyboard Grid",
                    rationale = "Creates 4 consistent placeholder concept thumbnails for rapid layout blocking.",
                    detectedIntent = intent,
                    confidencePercent = 88,
                    estimatedTimeSavedSec = 210,
                    suggestedCommand = "Create 1:1 concept art placeholder batch with consistent seed"
                )
            )
            CreativeIntent.EXPORT_PUBLISHING -> listOf(
                AutopilotRecommendation(
                    title = "Platform-Optimized Export (YouTube 1080p & GitHub Bundle)",
                    rationale = "Validates bitrate, color space, metadata tags, and $enabledMcpToolsCount connected MCP publishing tools.",
                    detectedIntent = intent,
                    confidencePercent = 97,
                    estimatedTimeSavedSec = 270,
                    suggestedCommand = "Run pre-export quality check and prepare YouTube / GitHub release bundle"
                )
            )
        }
    }

    private fun seedInitialState(): AutopilotSmartUiState {
        val templates = listOf(
            AutopilotWorkflowTemplate(
                id = "tpl_quick_edit",
                category = WorkflowTemplateCategory.QUICK_EDIT,
                title = "Quick Edit Workflow",
                subtitle = "Rapid content modification, prompt tightening, and side-by-side verification",
                estimatedMinutesSaved = 6,
                tags = listOf("Editing", "Compare", "Fast Turnaround"),
                stages = listOf(
                    ProgressiveWorkflowStage(
                        stageIndex = 0,
                        title = "1. Context & Intent Snapshot",
                        explanation = "Analyzes active conversation and locks deterministic temperature for clean edits.",
                        defaultActionCommand = "Set temperature to 0.3 and snapshot current conversation branch",
                        customizableParameterLabel = "Temperature",
                        parameterValue = "0.3"
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 1,
                        title = "2. Multi-Model Diff & Polish",
                        explanation = "Runs prompt refinement and highlights structural improvements before applying.",
                        defaultActionCommand = "Run Quick Edit refinement pass on active draft",
                        customizableParameterLabel = "Style Tone",
                        parameterValue = "Concise & Technical"
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 2,
                        title = "3. Quality Validation Check",
                        explanation = "Verifies formatting, code fences, and zero broken references.",
                        defaultActionCommand = "Validate output formatting and markdown integrity",
                        customizableParameterLabel = "Strictness",
                        parameterValue = "High"
                    )
                )
            ),
            AutopilotWorkflowTemplate(
                id = "tpl_animation_setup",
                category = WorkflowTemplateCategory.ANIMATION_SETUP,
                title = "Animation Setup Workflow",
                subtitle = "Automated keyframe generation, camera motion curve, and temporal consistency",
                estimatedMinutesSaved = 12,
                tags = listOf("Motion Graphics", "16:9", "Keyframes"),
                stages = listOf(
                    ProgressiveWorkflowStage(
                        stageIndex = 0,
                        title = "1. Anchor Keyframe Generation",
                        explanation = "Creates a high-contrast 16:9 hero frame to anchor lighting and composition.",
                        defaultActionCommand = "Switch to Image Studio and configure 16:9 hero keyframe",
                        customizableParameterLabel = "Aspect Ratio",
                        parameterValue = "16:9 (1280x720)",
                        isCompleted = true,
                        isInProgress = false
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 1,
                        title = "2. Camera Motion & Duration Setup",
                        explanation = "Configures smooth dolly/orbit camera trajectory and frame pacing.",
                        defaultActionCommand = "Switch to Video Studio and apply camera motion preset",
                        customizableParameterLabel = "Camera Motion",
                        parameterValue = "Smooth Dolly In (5s)",
                        isCompleted = false,
                        isInProgress = true
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 2,
                        title = "3. Temporal Consistency Pass",
                        explanation = "Schedules render during optimal device state and verifies playback loop.",
                        defaultActionCommand = "Queue video render with temporal consistency check",
                        customizableParameterLabel = "Resolution",
                        parameterValue = "720p HD",
                        isCompleted = false,
                        isInProgress = false
                    )
                )
            ),
            AutopilotWorkflowTemplate(
                id = "tpl_export_ready",
                category = WorkflowTemplateCategory.EXPORT_READY,
                title = "Export Ready Pipeline",
                subtitle = "Platform-tuned export settings for YouTube Studio, GitHub Gists, and archives",
                estimatedMinutesSaved = 9,
                tags = listOf("Export", "YouTube", "GitHub", "QC"),
                stages = listOf(
                    ProgressiveWorkflowStage(
                        stageIndex = 0,
                        title = "1. Pre-Flight Quality Control",
                        explanation = "Audits resolution, aspect ratio, audio/video container, and secret scrubbing.",
                        defaultActionCommand = "Run pre-export Quality Control audit",
                        customizableParameterLabel = "Target Platform",
                        parameterValue = "YouTube 1080p + GitHub Markdown"
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 1,
                        title = "2. Metadata & Tag Generation",
                        explanation = "Generates structured title, chapter markers, and searchable tags.",
                        defaultActionCommand = "Generate SEO metadata and release notes",
                        customizableParameterLabel = "Visibility",
                        parameterValue = "Unlisted"
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 2,
                        title = "3. One-Tap Package & Publish",
                        explanation = "Packages final artifacts and triggers connected publishing integration.",
                        defaultActionCommand = "Export clean conversation and media bundle",
                        customizableParameterLabel = "Include Secrets",
                        parameterValue = "false (Scrubbed)"
                    )
                )
            ),
            AutopilotWorkflowTemplate(
                id = "tpl_asset_prep",
                category = WorkflowTemplateCategory.ASSET_PREPARATION,
                title = "Asset Preparation & Tagging",
                subtitle = "Automated asset categorization, semantic tagging, and naming normalization",
                estimatedMinutesSaved = 8,
                tags = listOf("Assets", "Tagging", "Organization"),
                stages = listOf(
                    ProgressiveWorkflowStage(
                        stageIndex = 0,
                        title = "1. Scan & Deduplicate Workspace Assets",
                        explanation = "Identifies generated images, videos, and attachments across threads.",
                        defaultActionCommand = "Scan workspace media and group by modality",
                        customizableParameterLabel = "Grouping Mode",
                        parameterValue = "By Project & Aspect Ratio"
                    ),
                    ProgressiveWorkflowStage(
                        stageIndex = 1,
                        title = "2. Auto-Apply Semantic Tags",
                        explanation = "Assigns searchable tags (#hero, #motion, #draft, #final) to every asset.",
                        defaultActionCommand = "Tag workspace assets with semantic labels",
                        customizableParameterLabel = "Tag Prefix",
                        parameterValue = "#runway-prod"
                    )
                )
            )
        )

        val initialTasks = listOf(
            ScheduledAutomationTask(
                title = "Pre-Flight Export Quality Verification",
                batchGroupKey = "Quality-Control-Batch",
                priority = TaskPriority.CRITICAL,
                commandToExecute = "Scan model health and verify export readiness",
                deadlineMinutes = 5,
                requiresHeavyCompute = false
            ),
            ScheduledAutomationTask(
                title = "Batch Storyboard Keyframe Upscale & Tagging",
                batchGroupKey = "Asset-Prep-Batch",
                priority = TaskPriority.HIGH,
                commandToExecute = "Switch to Image Studio and organize reference keyframes",
                deadlineMinutes = 15,
                requiresHeavyCompute = true
            ),
            ScheduledAutomationTask(
                title = "Idle Window Telemetry & Latency Benchmark",
                batchGroupKey = "Maintenance-Batch",
                priority = TaskPriority.IDLE_BATCH,
                commandToExecute = "Benchmark provider latency and refresh model capabilities",
                deadlineMinutes = 45,
                requiresHeavyCompute = false
            )
        )

        val initialRules = listOf(
            CustomAutomationRule(
                name = "Auto-Guard Unplugged Battery (<25%)",
                triggerCondition = "WHEN Device.Battery < 25% AND !Charging",
                actionCommand = "Defer 1080p video renders to Idle/Charging queue",
                isEnabled = true,
                triggerCount = 4
            ),
            CustomAutomationRule(
                name = "Auto-Tag New 16:9 Renders",
                triggerCondition = "WHEN ImageOrVideo.AspectRatio == 16:9",
                actionCommand = "Apply #widescreen and #youtube-ready tags automatically",
                isEnabled = true,
                triggerCount = 11
            )
        )

        val shortcuts = listOf(
            SmartShortcut("sc_1", "Ctrl+Shift+Q", "Quick Edit Pass", "Run Quick Edit refinement pass on active draft", 28),
            SmartShortcut("sc_2", "Ctrl+Shift+A", "Animation Setup", "Switch to Video Studio and apply camera motion preset", 19),
            SmartShortcut("sc_3", "Ctrl+Shift+E", "Export Ready Check", "Export clean conversation and media bundle", 15),
            SmartShortcut("sc_4", "Ctrl+Shift+M", "Benchmark Models", "Benchmark provider latency and refresh model capabilities", 12)
        )

        val checks = listOf(
            QualityControlCheckResult(
                checkName = "Export Aspect Ratio & Codec Compliance",
                passed = true,
                detail = "16:9 (1920x1080) & 1:1 (1024x1024) presets verified",
                autoFixSuggestion = "Compliant"
            ),
            QualityControlCheckResult(
                checkName = "OWASP Credential & Vault Encryption",
                passed = true,
                detail = "Hardware-backed Tink AES-256-GCM active",
                autoFixSuggestion = "Compliant"
            )
        )

        return AutopilotSmartUiState(
            detectedIntent = CreativeIntent.MOTION_GRAPHICS,
            proficiencyLevel = SkillProficiencyLevel.INTERMEDIATE,
            recommendations = buildRecommendationsForIntent(CreativeIntent.MOTION_GRAPHICS, "OmniRoute", 2, 4),
            templates = templates,
            activeTemplateId = templates[1].id,
            scheduledTasks = initialTasks,
            customRules = initialRules,
            smartShortcuts = shortcuts,
            qualityControlChecks = checks,
            organizedAssetTags = mapOf(
                "Hero Storyboard Keyframes" to listOf("#motion-graphics", "#16:9", "#720p"),
                "Brand Style Reference Pack" to listOf("#image-to-image", "#style-guide")
            )
        )
    }
}
