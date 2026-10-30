package com.singu.xfriend

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/** Only opaque IDs, kind and ordering are plaintext; all customer content is an AES-GCM blob. */
@Entity(tableName = "sales_entries", primaryKeys = ["kind", "id"])
internal data class SalesEntry(val kind: String, val id: String, val position: Int, val payload: ByteArray)

@Dao
internal interface SalesEntryDao {
    @Query("SELECT * FROM sales_entries WHERE kind = :kind ORDER BY position")
    fun entries(kind: String): List<SalesEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: SalesEntry)

    @Query("DELETE FROM sales_entries WHERE kind = :kind AND id = :id")
    fun remove(kind: String, id: String)
}

@Database(entities = [SalesEntry::class], version = 1, exportSchema = false)
internal abstract class SalesDatabase : RoomDatabase() {
    abstract fun entries(): SalesEntryDao

    companion object {
        private val databases = mutableMapOf<String, SalesDatabase>()
        @Synchronized fun open(context: Context, scope: String): SalesDatabase {
            require(scope.matches(Regex("[a-f0-9]{64}")))
            val path = File(context.noBackupFilesDir, "sales/$scope/crm.db").absolutePath
            return databases.getOrPut(path) {
                Room.databaseBuilder(context.applicationContext, SalesDatabase::class.java, path).build()
            }
        }
    }
}
