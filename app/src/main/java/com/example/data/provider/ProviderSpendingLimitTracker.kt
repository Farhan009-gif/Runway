package com.example.data.provider

import com.example.data.local.UsageRecordEntity
import java.util.Calendar
import java.util.Locale

/**
 * Monthly USD spending limit calculations and threshold evaluation per provider.
 */
object ProviderSpendingLimitTracker {

    /**
     * Calculates the sum of estimated costs incurred by [providerInstanceId] in the current calendar month.
     */
    fun calculateMonthlySpendUsd(
        providerInstanceId: String,
        usageRecords: List<UsageRecordEntity>,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): Double {
        val refCal = Calendar.getInstance().apply { timeInMillis = referenceTimeMs }
        val targetYear = refCal.get(Calendar.YEAR)
        val targetMonth = refCal.get(Calendar.MONTH)

        val itemCal = Calendar.getInstance()
        return usageRecords
            .filter { it.providerInstanceId == providerInstanceId }
            .filter { record ->
                itemCal.timeInMillis = record.createdAt
                itemCal.get(Calendar.YEAR) == targetYear &&
                    itemCal.get(Calendar.MONTH) == targetMonth
            }
            .sumOf { it.estimatedCostUsd }
    }

    /**
     * Returns the spending progress ratio (0.0 to 1.0+).
     */
    fun calculateSpendingRatio(spendUsd: Double, limitUsd: Double): Float {
        if (limitUsd <= 0.0) return 0f
        return (spendUsd / limitUsd).toFloat().coerceAtLeast(0f)
    }

    /**
     * True if spending is at or above 80% of the monthly limit.
     */
    fun isAtOrAboveWarningThreshold(spendUsd: Double, limitUsd: Double): Boolean {
        if (limitUsd <= 0.0) return false
        return (spendUsd / limitUsd) >= 0.80
    }

    /**
     * True if spending has reached or exceeded 100% of the monthly limit.
     */
    fun isLimitReached(spendUsd: Double, limitUsd: Double): Boolean {
        if (limitUsd <= 0.0) return false
        return spendUsd >= limitUsd
    }

    /**
     * Formats the progress string: e.g. "$3.20 of $5.00 this month".
     */
    fun formatMonthlyProgress(spendUsd: Double, limitUsd: Double): String {
        return String.format(Locale.US, "$%.2f of $%.2f this month", spendUsd, limitUsd)
    }
}
