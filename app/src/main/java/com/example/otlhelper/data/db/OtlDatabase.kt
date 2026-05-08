package com.example.otlhelper.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.otlhelper.data.db.dao.BaseMetaDao
import com.example.otlhelper.data.db.dao.FeedItemDao
import com.example.otlhelper.data.db.dao.MolRecordDao
import com.example.otlhelper.data.db.dao.PendingActionDao
import com.example.otlhelper.data.db.entity.BaseMetaEntity
import com.example.otlhelper.data.db.entity.FeedItemEntity
import com.example.otlhelper.data.db.entity.MolRecordEntity
import com.example.otlhelper.data.db.entity.PendingActionEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        MolRecordEntity::class,
        BaseMetaEntity::class,
        FeedItemEntity::class,
        PendingActionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class OtlDatabase : RoomDatabase() {

    abstract fun molRecordDao(): MolRecordDao
    abstract fun baseMetaDao(): BaseMetaDao
    abstract fun feedItemDao(): FeedItemDao
    abstract fun pendingActionDao(): PendingActionDao

    companion object {
        private const val DB_NAME = "otl_local.db"

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No structural changes — tables are identical.
                // Data from the old unencrypted DB is intentionally not migrated;
                // the base will re-sync from server on next launch.
            }
        }

        fun create(context: Context, passphrase: CharArray): OtlDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(String(passphrase).toByteArray(Charsets.UTF_8))
            return Room.databaseBuilder(context, OtlDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
