package io.nekohasekai.sagernet.database.traffic

import androidx.room.Entity

@Entity(
    tableName = "app_traffic_records",
    primaryKeys = ["timestamp", "packageName"]
)
data class AppTrafficRecord(
    val timestamp: Long,      // 15-minute slot start timestamp (milliseconds)
    val date: String,         // "yyyy-MM-dd"
    val packageName: String,  // Application package name
    val uid: Int,             // Application UID
    var rxProxy: Long = 0L,   // Proxied download traffic (bytes)
    var txProxy: Long = 0L,   // Proxied upload traffic (bytes)
) {
    val total: Long get() = rxProxy + txProxy
}

/**
 * Aggregated summary across time intervals for a single app
 */
data class AppTrafficSummary(
    val packageName: String,
    val uid: Int,
    val rxProxy: Long,
    val txProxy: Long,
) {
    val total: Long get() = rxProxy + txProxy
}
