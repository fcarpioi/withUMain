package com.controlparental.jerico.usage

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

class AppUsageManager(private val context: Context) {

    fun getAppUsageStats(intervalMillis: Long = 24 * 60 * 60 * 1000L): List<UsageStats>? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - intervalMillis
        Log.d("AppUsageManager", "Querying usage stats from $startTime to $endTime")
        val usageStatsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )
        if (usageStatsList.isEmpty()) {
            Log.w("AppUsageManager", "No usage stats available. Check that Usage Access is granted.")
            return null
        }
        return usageStatsList
    }

    fun logUsageStats(intervalMillis: Long = 24 * 60 * 60 * 1000L) {
        val stats = getAppUsageStats(intervalMillis)
        stats?.forEach { usage ->
            Log.d("AppUsageManager", "Package: ${usage.packageName}, TotalTimeForeground: ${usage.totalTimeInForeground}")
        }
    }
}