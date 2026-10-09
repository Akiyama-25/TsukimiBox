package io.nekohasekai.sagernet.database.traffic

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_records")
data class TrafficRecord(
    @PrimaryKey
    val timestamp: Long, // 15-minute slot start timestamp (milliseconds)
    val date: String,    // "yyyy-MM-dd"
    val hour: Int,       // 0..23
    val minute: Int,     // 0, 15, 30, 45
    var rxProxy: Long = 0L,
    var txProxy: Long = 0L,
    var rxDirect: Long = 0L,
    var txDirect: Long = 0L,
) {
    // Only count proxied traffic, exclude direct traffic that bypasses proxy
    val rxTotal: Long get() = rxProxy
    val txTotal: Long get() = txProxy
    val total: Long get() = rxTotal + txTotal
}
