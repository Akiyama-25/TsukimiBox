package io.nekohasekai.sagernet.database.traffic

import androidx.room.*

@Dao
abstract class AppTrafficRecordDao {

    @Query("""
        SELECT packageName, uid, SUM(rxProxy) as rxProxy, SUM(txProxy) as txProxy 
        FROM app_traffic_records 
        WHERE timestamp >= :startTime AND timestamp < :endTime 
        GROUP BY packageName 
        ORDER BY (SUM(rxProxy) + SUM(txProxy)) DESC
    """)
    abstract fun getAppTrafficBetween(startTime: Long, endTime: Long): List<AppTrafficSummary>

    @Query("""
        SELECT packageName, uid, SUM(rxProxy) as rxProxy, SUM(txProxy) as txProxy 
        FROM app_traffic_records 
        WHERE date = :date 
        GROUP BY packageName 
        ORDER BY (SUM(rxProxy) + SUM(txProxy)) DESC
    """)
    abstract fun getAppTrafficByDate(date: String): List<AppTrafficSummary>

    @Query("""
        SELECT packageName, uid, SUM(rxProxy) as rxProxy, SUM(txProxy) as txProxy 
        FROM app_traffic_records 
        GROUP BY packageName 
        ORDER BY (SUM(rxProxy) + SUM(txProxy)) DESC
    """)
    abstract fun getAllAppTraffic(): List<AppTrafficSummary>

    @Query("SELECT * FROM app_traffic_records WHERE timestamp = :timestamp AND packageName = :packageName LIMIT 1")
    abstract fun getRecord(timestamp: Long, packageName: String): AppTrafficRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertOrUpdate(record: AppTrafficRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertOrUpdateAll(records: List<AppTrafficRecord>)

    @Transaction
    open fun addDelta(
        timestamp: Long,
        date: String,
        packageName: String,
        uid: Int,
        rxProxy: Long,
        txProxy: Long,
    ) {
        val existing = getRecord(timestamp, packageName)
        if (existing == null) {
            insertOrUpdate(
                AppTrafficRecord(
                    timestamp = timestamp,
                    date = date,
                    packageName = packageName,
                    uid = uid,
                    rxProxy = rxProxy,
                    txProxy = txProxy,
                )
            )
        } else {
            existing.rxProxy += rxProxy
            existing.txProxy += txProxy
            insertOrUpdate(existing)
        }
    }

    @Query("DELETE FROM app_traffic_records WHERE timestamp < :cutoffTime")
    abstract fun deleteOlderThan(cutoffTime: Long)

    @Query("DELETE FROM app_traffic_records")
    abstract fun clearAll()
}
