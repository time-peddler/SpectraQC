package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ReportMetaEntity::class,
        TemplateConfigEntity::class,
        FieldCandidateEntity::class,
        EquipmentEntity::class,
        ComponentPartEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(ElementMapConverter::class)
abstract class SpectraDatabase : RoomDatabase() {
    abstract fun inspectionDao(): InspectionDao

    companion object {
        @Volatile
        private var INSTANCE: SpectraDatabase? = null

        fun getInstance(context: Context): SpectraDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpectraDatabase::class.java,
                    "spectra_qc_local.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
