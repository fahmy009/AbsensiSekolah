package com.example.aplikasiabsensimojogemi02

import android.content.Context
import androidx.room.*

@Entity(tableName = "siswa_offline")
data class SiswaEntity(
    @PrimaryKey val nisn: String,
    val nama: String,
    val status: String,
    val jamMasuk: String,
    val jamPulang: String,
    val jamSholat: String,
    val keterangan: String,
    val isSynced: Boolean = true
)

@Dao
interface SiswaDao {
    @Query("SELECT * FROM siswa_offline")
    suspend fun getAllSiswa(): List<SiswaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(siswa: List<SiswaEntity>)

    @Query("DELETE FROM siswa_offline")
    suspend fun deleteAll()
    
    @Update
    suspend fun updateSiswa(siswa: SiswaEntity)
}

@Database(entities = [SiswaEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siswaDao(): SiswaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "absensi_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
