package io.nekohasekai.sagernet.database.traffic

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Database(entities = [TrafficRecord::class, AppTrafficRecord::class], version = 2, exportSchema = false)
abstract class TrafficDatabase : RoomDatabase() {

    abstract fun trafficRecordDao(): TrafficRecordDao
    abstract fun appTrafficRecordDao(): AppTrafficRecordDao

    companion object {
        @OptIn(DelicateCoroutinesApi::class)
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_TRAFFIC).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, TrafficDatabase::class.java, Key.DB_TRAFFIC)
                .setJournalMode(JournalMode.TRUNCATE)
                .allowMainThreadQueries()
                .enableMultiInstanceInvalidation()
                .fallbackToDestructiveMigration()
                .setQueryExecutor { GlobalScope.launch { it.run() } }
                .build()
        }

        val trafficDao get() = instance.trafficRecordDao()
        val appTrafficDao get() = instance.appTrafficRecordDao()
    }
}
