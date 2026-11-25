package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ReportMetaEntity::class,
        TemplateConfigEntity::class,
        FieldCandidateEntity::class,
        EquipmentEntity::class,
        ComponentPartEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(ElementMapConverter::class)
abstract class SpectraDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: SpectraDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE component_parts ADD COLUMN ndElements TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): SpectraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpectraDatabase::class.java,
                    "spectra_qc_local.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
