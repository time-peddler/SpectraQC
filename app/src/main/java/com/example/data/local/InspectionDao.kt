package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface InspectionDao {

    @Query("SELECT * FROM report_meta WHERE id = 'default' LIMIT 1")
    fun observeMeta(): Flow<ReportMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: ReportMetaEntity)

    @Query("SELECT * FROM template_config WHERE id = 'default' LIMIT 1")
    fun observeTemplateConfig(): Flow<TemplateConfigEntity?>

    @Query("SELECT * FROM template_config WHERE id = 'default' LIMIT 1")
    suspend fun getTemplateConfigOnce(): TemplateConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTemplateConfig(config: TemplateConfigEntity)

    @Query("SELECT * FROM field_candidates ORDER BY useCount DESC, updatedAt DESC")
    fun observeAllCandidates(): Flow<List<FieldCandidateEntity>>

    @Query("SELECT * FROM field_candidates WHERE fieldKey = :fieldKey AND value = :value LIMIT 1")
    suspend fun findCandidate(fieldKey: String, value: String): FieldCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidate(candidate: FieldCandidateEntity)

    @Query("DELETE FROM field_candidates WHERE fieldKey = :fieldKey AND value = :value")
    suspend fun deleteCandidate(fieldKey: String, value: String)

    @Query("SELECT * FROM equipments ORDER BY sequenceNo ASC, tagNumber ASC")
    fun observeEquipments(): Flow<List<EquipmentEntity>>

    @Query("SELECT * FROM equipments ORDER BY sequenceNo ASC, tagNumber ASC")
    suspend fun getAllEquipmentsOnce(): List<EquipmentEntity>

    @Query("SELECT * FROM component_parts WHERE equipmentId = :equipmentId ORDER BY sortOrder ASC")
    fun observeParts(equipmentId: String): Flow<List<ComponentPartEntity>>

    @Query("SELECT * FROM component_parts WHERE equipmentId = :equipmentId ORDER BY sortOrder ASC")
    suspend fun getPartsForEquipmentOnce(equipmentId: String): List<ComponentPartEntity>

    @Query("SELECT * FROM component_parts ORDER BY sortOrder ASC")
    suspend fun getAllPartsOnce(): List<ComponentPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEquipment(equipment: EquipmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<ComponentPartEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPart(part: ComponentPartEntity)

    @Update
    suspend fun updatePart(part: ComponentPartEntity)

    @Query("DELETE FROM component_parts WHERE id = :partId")
    suspend fun deletePartById(partId: String)

    @Query("UPDATE equipments SET totalParts = :totalParts, updatedAt = :updatedAt WHERE id = :equipmentId")
    suspend fun updateEquipmentTotalParts(equipmentId: String, totalParts: Long, updatedAt: Long)

    @Query("UPDATE equipments SET totalParts = :totalParts, completedParts = :completedParts, updatedAt = :updatedAt WHERE id = :equipmentId")
    suspend fun updateEquipmentCounts(equipmentId: String, totalParts: Long, completedParts: Long, updatedAt: Long)

    @Query("UPDATE equipments SET completedParts = :completedParts, updatedAt = :updatedAt WHERE id = :equipmentId")
    suspend fun updateEquipmentCompletedParts(equipmentId: String, completedParts: Long, updatedAt: Long)

    @Query("DELETE FROM equipments WHERE id = :equipmentId")
    suspend fun deleteEquipmentById(equipmentId: String)

    @Transaction
    suspend fun insertEquipmentWithParts(
        equipment: EquipmentEntity,
        parts: List<ComponentPartEntity>
    ) {
        insertEquipment(equipment)
        if (parts.isNotEmpty()) {
            insertParts(parts)
        }
    }

    @Transaction
    suspend fun addCustomPartAndIncrementCount(
        part: ComponentPartEntity,
        newTotalParts: Long,
        updatedAt: Long
    ) {
        insertPart(part)
        updateEquipmentTotalParts(part.equipmentId, newTotalParts, updatedAt)
    }

    @Transaction
    suspend fun updatePartAndProgress(
        part: ComponentPartEntity,
        completedParts: Long,
        updatedAt: Long
    ) {
        updatePart(part)
        updateEquipmentCompletedParts(part.equipmentId, completedParts, updatedAt)
    }

    @Transaction
    suspend fun deletePartAndUpdateCounts(
        equipmentId: String,
        partId: String,
        newTotalParts: Long,
        newCompletedParts: Long,
        updatedAt: Long
    ) {
        deletePartById(partId)
        updateEquipmentCounts(equipmentId, newTotalParts, newCompletedParts, updatedAt)
    }
}
