package io.nekohasekai.sagernet.database.traffic

import androidx.room.*

@Dao
abstract class TrafficRecordDao {

    @Query("SELECT * FROM traffic_records WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp ASC")
    abstract fun getRecordsBetween(startTime: Long, endTime: Long): List<TrafficRecord>

    @Query("SELECT * FROM traffic_records WHERE date = :date ORDER BY timestamp ASC")
    abstract fun getRecordsByDate(date: String): List<TrafficRecord>

    @Query("SELECT * FROM traffic_records WHERE timestamp >= :startTime ORDER BY timestamp ASC")
    abstract fun getRecordsSince(startTime: Long): List<TrafficRecord>

    @Query("SELECT * FROM traffic_records WHERE timestamp = :timestamp LIMIT 1")
    abstract fun getRecordByTimestamp(timestamp: Long): TrafficRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertOrUpdate(record: TrafficRecord)

    @Transaction
    open fun addDelta(
        timestamp: Long,
        date: String,
        hour: Int,
        minute: Int,
        rxProxy: Long,
        txProxy: Long,
        rxDirect: Long,
        txDirect: Long,
    ) {
        val existing = getRecordByTimestamp(timestamp)
        if (existing == null) {
            insertOrUpdate(
                TrafficRecord(
                    timestamp = timestamp,
                    date = date,
                    hour = hour,
                    minute = minute,
                    rxProxy = rxProxy,
                    txProxy = txProxy,
                    rxDirect = rxDirect,
                    txDirect = txDirect,
                )
            )
        } else {
            existing.rxProxy += rxProxy
            existing.txProxy += txProxy
            existing.rxDirect += rxDirect
            existing.txDirect += txDirect
            insertOrUpdate(existing)
        }
    }

    @Query("DELETE FROM traffic_records WHERE timestamp < :cutoffTime")
    abstract fun deleteOlderThan(cutoffTime: Long)

    @Query("DELETE FROM traffic_records")
    abstract fun clearAll()
}
