package com.example.data.provider

import com.example.data.local.UsageRecordEntity
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Evaluates monthly USD spending limits per provider using existing [UsageRecordEntity] logs.
 *
 * - Formats progress text as "$3.20 of $5.00 this month".
 * - Triggers an 80% warning banner in chat when monthly spend >= 80% of the limit.
 * - Blocks requests at 100% with "Monthly limit reached for <provider>. Raise the limit in Settings to continue."
 * - Allows at most one override per chat.
 */
object ProviderSpendingLimitEvaluator {

    const val WARNING_THRESHOLD_RATIO: Double = 0.80
    const val BLOCK_THRESHOLD_RATIO: Double = 1.00

    data class ProviderSpendingStatus(
        val providerId: String,
        val providerName: String,
        val spentThisMonthUsd: Double,
        val monthlyLimitUsd: Double?,
        val progressRatio: Float,
        val progressText: String,
        val isWarning80Percent: Boolean,
        val isLimitReached100Percent: Boolean,
        val isBlocked: Boolean,
        val canOverrideOnceInChat: Boolean,
        val warningBannerText: String?,
        val blockedBannerText: String?
    ) {
        val providerInstanceId: String get() = providerId
        val providerDisplayName: String get() = providerName
        val monthlySpendUsd: Double get() = spentThisMonthUsd
        val isWarning: Boolean get() = isWarning80Percent && !isBlocked
        val warningMessage: String? get() = warningBannerText
        val blockedMessage: String? get() = blockedBannerText
        val canOverrideOnce: Boolean get() = canOverrideOnceInChat
    }

    fun startOfMonthMillis(referenceTimeMillis: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = referenceTimeMillis
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun calculateMonthlySpendUsd(
        providerId: String,
        usageRecords: List<UsageRecordEntity>,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): Double {
        val monthStart = startOfMonthMillis(referenceTimeMillis)
        return usageRecords
            .asSequence()
            .filter { it.providerInstanceId == providerId && it.createdAt >= monthStart }
            .sumOf { it.estimatedCostUsd.coerceAtLeast(0.0) }
    }

    fun calculateMonthlySpendUsd(
        usageRecords: List<UsageRecordEntity>,
        providerInstanceId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Double = calculateMonthlySpendUsd(
        providerId = providerInstanceId,
        usageRecords = usageRecords,
        referenceTimeMillis = nowMillis
    )

    fun formatMonthlyProgressText(spentUsd: Double, limitUsd: Double?): String {
        val safeSpent = spentUsd.coerceAtLeast(0.0)
        if (limitUsd == null || limitUsd <= 0.0) {
            return String.format(Locale.US, "$%.2f this month (No limit)", safeSpent)
        }
        return String.format(Locale.US, "$%.2f of $%.2f this month", safeSpent, limitUsd)
    }

    fun formatBlockedMessage(providerName: String): String {
        val cleanName = providerName.trim().ifBlank { "this provider" }
        return "Monthly limit reached for $cleanName. Raise the limit in Settings to continue."
    }

    fun formatWarningMessage(providerName: String, spentUsd: Double, limitUsd: Double): String {
        val cleanName = providerName.trim().ifBlank { "Provider" }
        val pct = if (limitUsd > 0.0) ((spentUsd / limitUsd) * 100.0).toInt() else 80
        return "$cleanName is at $pct% of its monthly limit (${formatMonthlyProgressText(spentUsd, limitUsd)})."
    }

    fun parseLimitsJson(rawJson: String?): Map<String, Double> {
        if (rawJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(rawJson)
            val result = mutableMapOf<String, Double>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optDouble(key, -1.0)
                if (!value.isNaN() && value > 0.0) {
                    result[key] = value
                }
            }
            result.toMap()
        }.getOrDefault(emptyMap())
    }

    fun serializeLimitsJson(limits: Map<String, Double>): String {
        val obj = JSONObject()
        for ((k, v) in limits) {
            if (k.isNotBlank() && v > 0.0 && !v.isNaN()) {
                obj.put(k, v)
            }
        }
        return obj.toString()
    }

    fun evaluate(
        providerId: String,
        providerName: String,
        spentThisMonthUsd: Double,
        monthlyLimitUsd: Double?,
        conversationId: String? = null,
        activeOverrideChatIds: Set<String> = emptySet(),
        usedOverrideChatIds: Set<String> = emptySet()
    ): ProviderSpendingStatus {
        val hasActiveOverride = conversationId != null && conversationId in activeOverrideChatIds
        val hasAlreadyUsedOverride = conversationId != null && conversationId in usedOverrideChatIds
        return evaluate(
            providerInstanceId = providerId,
            providerDisplayName = providerName,
            monthlySpendUsd = spentThisMonthUsd,
            monthlyLimitUsd = monthlyLimitUsd,
            hasActiveOverrideForChat = hasActiveOverride,
            hasUsedOverrideInChat = hasAlreadyUsedOverride
        )
    }

    fun evaluate(
        providerInstanceId: String,
        providerDisplayName: String,
        monthlySpendUsd: Double,
        monthlyLimitUsd: Double?,
        hasActiveOverrideForChat: Boolean = false,
        hasUsedOverrideInChat: Boolean = false
    ): ProviderSpendingStatus {
        val validLimit = monthlyLimitUsd?.takeIf { it > 0.0 }
        val safeSpent = monthlySpendUsd.coerceAtLeast(0.0)
        if (validLimit == null) {
            return ProviderSpendingStatus(
                providerId = providerInstanceId,
                providerName = providerDisplayName,
                spentThisMonthUsd = safeSpent,
                monthlyLimitUsd = null,
                progressRatio = 0f,
                progressText = formatMonthlyProgressText(safeSpent, null),
                isWarning80Percent = false,
                isLimitReached100Percent = false,
                isBlocked = false,
                canOverrideOnceInChat = false,
                warningBannerText = null,
                blockedBannerText = null
            )
        }

        val rawRatio = safeSpent / validLimit
        val clampedProgress = rawRatio.toFloat().coerceIn(0f, 1f)
        val progressText = formatMonthlyProgressText(safeSpent, validLimit)
        val reached100 = rawRatio >= BLOCK_THRESHOLD_RATIO - 1e-9
        val reached80 = rawRatio >= WARNING_THRESHOLD_RATIO - 1e-9

        val isBlocked = reached100 && !hasActiveOverrideForChat
        val canOverrideOnce = reached100 && !hasActiveOverrideForChat && !hasUsedOverrideInChat

        val blockedMsg = if (reached100) formatBlockedMessage(providerDisplayName) else null
        val warningMsg = if (reached80 && !isBlocked) {
            formatWarningMessage(providerDisplayName, safeSpent, validLimit)
        } else null

        return ProviderSpendingStatus(
            providerId = providerInstanceId,
            providerName = providerDisplayName,
            spentThisMonthUsd = safeSpent,
            monthlyLimitUsd = validLimit,
            progressRatio = clampedProgress,
            progressText = progressText,
            isWarning80Percent = reached80,
            isLimitReached100Percent = reached100,
            isBlocked = isBlocked,
            canOverrideOnceInChat = canOverrideOnce,
            warningBannerText = warningMsg,
            blockedBannerText = blockedMsg
        )
    }
}
