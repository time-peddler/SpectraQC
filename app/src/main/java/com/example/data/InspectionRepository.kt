package com.example.data

import com.example.data.local.ComponentPartEntity
import com.example.data.local.EquipmentEntity
import com.example.data.local.FieldCandidateEntity
import com.example.data.local.InspectionDao
import com.example.data.local.ReportMetaEntity
import com.example.data.local.TemplateConfigEntity
import com.example.model.ComponentInspection
import com.example.model.DefaultFieldCandidates
import com.example.model.EquipmentRecord
import com.example.model.EquipmentTemplateType
import com.example.model.FieldCategory
import com.example.model.InspectionReportMeta
import com.example.model.PartStatus
import com.example.model.SectionType
import com.example.model.TemplateFieldDef
import com.example.model.TemplatePresets
import com.example.model.TemplateSchemaConfig
import com.example.util.PhotoStorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class InspectionRepository(
    private val dao: InspectionDao
) {
    fun observeMeta(): Flow<InspectionReportMeta> =
        dao.observeMeta().map { entity -> entity?.toDomain() ?: InspectionReportMeta() }

    fun observeTemplateConfig(): Flow<TemplateSchemaConfig> =
        dao.observeTemplateConfig().map { entity ->
            TemplateSchemaConfig.fromJsonString(entity?.fieldsJson)
        }

    fun observeCandidatesByField(): Flow<Map<String, List<String>>> =
        dao.observeAllCandidates().map { entities ->
            val groupedHistory = entities.groupBy { it.fieldKey }
                .mapValues { (_, list) -> list.map { it.value } }
            val combined = linkedMapOf<String, List<String>>()
            val allKeys = (DefaultFieldCandidates.builtInByFieldKey.keys + groupedHistory.keys).distinct()
            for (key in allKeys) {
                val history = groupedHistory[key].orEmpty()
                val defaults = DefaultFieldCandidates.builtInByFieldKey[key].orEmpty()
                combined[key] = (history + defaults).distinct()
            }
            combined
        }

    suspend fun saveMeta(meta: InspectionReportMeta): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            val cleaned = meta.copy(
                id = "default",
                processName = meta.processName.trim().ifBlank { "未命名工序" },
                docTitle = meta.docTitle.trim().ifBlank { "设备&部件材质成分分析表" },
                docCode = meta.docCode.trim(),
                version = meta.version.trim(),
                securityLevel = meta.securityLevel.trim(),
                approver = meta.approver.trim(),
                effectiveDate = meta.effectiveDate.trim(),
                updatedAt = now
            )
            dao.upsertMeta(ReportMetaEntity.fromDomain(cleaned))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveTemplateConfig(config: TemplateSchemaConfig): Result<Unit> {
        return try {
            dao.upsertTemplateConfig(
                TemplateConfigEntity(
                    id = "default",
                    fieldsJson = config.toJsonString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleTemplateField(fieldKey: String, enabled: Boolean): Result<Unit> {
        return try {
            val current = TemplateSchemaConfig.fromJsonString(dao.getTemplateConfigOnce()?.fieldsJson)
            val updated = current.fields.map { f ->
                if (f.key == fieldKey && !f.isCoreRequired) f.copy(enabled = enabled) else f
            }
            saveTemplateConfig(TemplateSchemaConfig(updated))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addCustomTemplateField(
        label: String,
        category: FieldCategory,
        enableWaterfallCandidates: Boolean
    ): Result<Unit> {
        return try {
            val cleanLabel = label.trim()
            require(cleanLabel.isNotEmpty()) { "字段名称不能为空" }

            val current = TemplateSchemaConfig.fromJsonString(dao.getTemplateConfigOnce()?.fieldsJson)
            val duplicate = current.fields.any {
                it.category == category && it.label.equals(cleanLabel, ignoreCase = true)
            }
            if (duplicate) {
                // Re-enable if it already existed
                val updated = current.fields.map { f ->
                    if (f.category == category && f.label.equals(cleanLabel, ignoreCase = true)) {
                        f.copy(enabled = true, enableWaterfallCandidates = enableWaterfallCandidates)
                    } else f
                }
                return saveTemplateConfig(TemplateSchemaConfig(updated))
            }

            val nextOrder = (current.fields.maxOfOrNull { it.sortOrder } ?: 0) + 1
            val key = if (category == FieldCategory.ELEMENT) {
                "elem_$cleanLabel"
            } else {
                "custom_${UUID.randomUUID().toString().take(8)}"
            }

            val newField = TemplateFieldDef(
                key = key,
                label = cleanLabel,
                category = category,
                enabled = true,
                isBuiltIn = false,
                isCoreRequired = false,
                enableWaterfallCandidates = if (category == FieldCategory.ELEMENT) false else enableWaterfallCandidates,
                sortOrder = nextOrder
            )

            // Seed helpful industrial suggestions if custom field is 口径 / 压力
            if (enableWaterfallCandidates) {
                DefaultFieldCandidates.suggestionsForCustomLabel(cleanLabel).forEach { suggestion ->
                    recordCandidateValue(key, suggestion)
                }
            }

            saveTemplateConfig(TemplateSchemaConfig(current.fields + newField))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeCustomTemplateField(fieldKey: String): Result<Unit> {
        return try {
            val current = TemplateSchemaConfig.fromJsonString(dao.getTemplateConfigOnce()?.fieldsJson)
            val filtered = current.fields.filterNot { it.key == fieldKey && !it.isBuiltIn }
            saveTemplateConfig(TemplateSchemaConfig(filtered))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetTemplateConfigToDefault(): Result<Unit> =
        saveTemplateConfig(TemplateSchemaConfig(TemplateSchemaConfig.defaultFields()))

    suspend fun recordCandidateValue(fieldKey: String, rawValue: String) {
        val clean = rawValue.trim()
        if (clean.isEmpty() || clean == "-") return
        val existing = dao.findCandidate(fieldKey, clean)
        val now = System.currentTimeMillis()
        if (existing != null) {
            dao.upsertCandidate(
                existing.copy(
                    useCount = existing.useCount + 1L,
                    updatedAt = now
                )
            )
        } else {
            dao.upsertCandidate(
                FieldCandidateEntity(
                    id = UUID.randomUUID().toString(),
                    fieldKey = fieldKey,
                    value = clean,
                    useCount = 1L,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun deleteCandidate(fieldKey: String, value: String) {
        runCatching { dao.deleteCandidate(fieldKey, value) }
    }

    fun observeEquipments(): Flow<List<EquipmentRecord>> =
        dao.observeEquipments().map { list -> list.map { it.toDomain() } }

    fun observeParts(equipmentId: String): Flow<List<ComponentInspection>> =
        dao.observeParts(equipmentId).map { list -> list.map { it.toDomain() } }

    suspend fun createEquipmentWithTemplate(
        sequenceNo: Long,
        tagNumber: String,
        equipmentName: String,
        specModel: String,
        systemName: String,
        sectionType: SectionType,
        workshopLocation: String,
        infraType: String,
        templateType: EquipmentTemplateType,
        customFields: Map<String, String> = emptyMap()
    ): Result<String> {
        return try {
            val cleanTag = tagNumber.trim()
            val cleanName = equipmentName.trim()
            require(cleanTag.isNotEmpty()) { "设备位号不能为空" }
            require(cleanName.isNotEmpty()) { "设备名称不能为空" }

            val now = System.currentTimeMillis()
            val eqId = UUID.randomUUID().toString()
            val seedParts = TemplatePresets.buildPartsForTemplate(templateType, sequenceNo)
            val completedInit = seedParts.count { it.defaultNonMetal }.toLong()
            val cleanSpec = specModel.trim()
            val cleanSystem = systemName.trim()
            val cleanCustom = customFields.mapValues { (_, v) -> v.trim() }.filterValues { it.isNotEmpty() }

            val eqEntity = EquipmentEntity(
                id = eqId,
                sequenceNo = sequenceNo.coerceIn(1L, 10000L),
                tagNumber = cleanTag,
                equipmentName = cleanName,
                specModel = cleanSpec,
                systemName = cleanSystem,
                sectionType = sectionType.name,
                workshopLocation = workshopLocation.trim(),
                infraType = infraType.trim(),
                templateType = templateType.name,
                customFields = cleanCustom,
                totalParts = seedParts.size.toLong(),
                completedParts = completedInit,
                createdAt = now,
                updatedAt = now
            )

            val partEntities = seedParts.mapIndexed { index, seed ->
                val status = if (seed.defaultNonMetal) PartStatus.NON_METAL else PartStatus.PENDING
                val matType = if (seed.defaultNonMetal) "非金属" else "金属"
                ComponentPartEntity(
                    id = UUID.randomUUID().toString(),
                    equipmentId = eqId,
                    codeNumber = seed.codeSuffix,
                    sortOrder = (index + 1).toLong(),
                    systemName = cleanSystem,
                    componentGroup = seed.componentGroup,
                    partName = seed.partName,
                    quantity = seed.quantity,
                    calloutNumber = seed.calloutNumber,
                    status = status.name,
                    materialType = matType,
                    detectedGrade = if (seed.defaultNonMetal) "非金属密封件" else "",
                    elements = emptyMap(),
                    cuZnImpurity = if (seed.defaultNonMetal) "0%" else "",
                    severityS = "",
                    motionState = "",
                    motionEval = "",
                    locationPos = "",
                    frequencyO = "",
                    detectionMethod = if (seed.defaultNonMetal) "目视/材质核查" else "便携式光谱仪",
                    detectionD = "",
                    failureConsequence = "",
                    riskLevel = "",
                    controlMeasure = "",
                    activityDescription = "",
                    ocrSummary = "",
                    inspectorNotes = "",
                    photoPath = "",
                    customFields = emptyMap(),
                    createdAt = now,
                    updatedAt = now
                )
            }

            dao.insertEquipmentWithParts(eqEntity, partEntities)

            // Save values to repeatable waterfall candidate history
            recordCandidateValue(TemplateSchemaConfig.KEY_EQ_NAME, cleanName)
            recordCandidateValue(TemplateSchemaConfig.KEY_EQ_SPEC, cleanSpec)
            recordCandidateValue(TemplateSchemaConfig.KEY_EQ_SYSTEM, cleanSystem)
            cleanCustom.forEach { (k, v) -> recordCandidateValue(k, v) }

            Result.success(eqId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun duplicateEquipment(
        source: EquipmentRecord,
        newSequenceNo: Long,
        newTagNumber: String,
        newSpecModel: String
    ): Result<String> {
        return try {
            val cleanTag = newTagNumber.trim()
            require(cleanTag.isNotEmpty()) { "新设备位号不能为空" }

            val sourceParts = dao.getPartsForEquipmentOnce(source.id)
            val now = System.currentTimeMillis()
            val newEqId = UUID.randomUUID().toString()
            val completedInit = sourceParts.count {
                PartStatus.fromRaw(it.status) == PartStatus.NON_METAL
            }.toLong()
            val resolvedSpec = newSpecModel.trim().ifBlank { source.specModel }

            val eqEntity = EquipmentEntity(
                id = newEqId,
                sequenceNo = newSequenceNo.coerceIn(1L, 10000L),
                tagNumber = cleanTag,
                equipmentName = source.equipmentName,
                specModel = resolvedSpec,
                systemName = source.systemName,
                sectionType = source.sectionType,
                workshopLocation = source.workshopLocation,
                infraType = source.infraType,
                templateType = source.templateType,
                customFields = source.customFields,
                totalParts = sourceParts.size.toLong(),
                completedParts = completedInit,
                createdAt = now,
                updatedAt = now
            )

            val clonedParts = sourceParts.mapIndexed { index, oldPart ->
                val isNonMetal = PartStatus.fromRaw(oldPart.status) == PartStatus.NON_METAL
                ComponentPartEntity(
                    id = UUID.randomUUID().toString(),
                    equipmentId = newEqId,
                    codeNumber = rewriteSequencePrefix(oldPart.codeNumber, newSequenceNo),
                    sortOrder = (index + 1).toLong(),
                    systemName = oldPart.systemName,
                    componentGroup = oldPart.componentGroup,
                    partName = oldPart.partName,
                    quantity = oldPart.quantity,
                    calloutNumber = oldPart.calloutNumber,
                    status = if (isNonMetal) PartStatus.NON_METAL.name else PartStatus.PENDING.name,
                    materialType = if (isNonMetal) "非金属" else "金属",
                    detectedGrade = if (isNonMetal) oldPart.detectedGrade else "",
                    elements = emptyMap(),
                    cuZnImpurity = if (isNonMetal) "0%" else "",
                    severityS = "",
                    motionState = "",
                    motionEval = "",
                    locationPos = "",
                    frequencyO = "",
                    detectionMethod = oldPart.detectionMethod,
                    detectionD = "",
                    failureConsequence = "",
                    riskLevel = "",
                    controlMeasure = "",
                    activityDescription = oldPart.activityDescription,
                    ocrSummary = "",
                    inspectorNotes = "",
                    photoPath = "",
                    customFields = emptyMap(),
                    createdAt = now,
                    updatedAt = now
                )
            }

            dao.insertEquipmentWithParts(eqEntity, clonedParts)
            recordCandidateValue(TemplateSchemaConfig.KEY_EQ_SPEC, resolvedSpec)
            Result.success(newEqId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addCustomPart(
        equipmentId: String,
        codeNumber: String,
        componentGroup: String,
        partName: String,
        quantity: String,
        systemName: String,
        currentTotalParts: Long,
        nextSortOrder: Long
    ): Result<String> {
        return try {
            require(equipmentId.isNotBlank() && partName.isNotBlank())
            val now = System.currentTimeMillis()
            val partId = UUID.randomUUID().toString()
            val cleanGroup = componentGroup.trim().ifBlank { "自定义部件" }
            val entity = ComponentPartEntity(
                id = partId,
                equipmentId = equipmentId,
                codeNumber = codeNumber.trim().ifBlank { nextSortOrder.toString() },
                sortOrder = nextSortOrder,
                systemName = systemName.trim(),
                componentGroup = cleanGroup,
                partName = partName.trim(),
                quantity = quantity.trim().ifBlank { "1" },
                calloutNumber = 0L,
                status = PartStatus.PENDING.name,
                materialType = "金属",
                detectedGrade = "",
                elements = emptyMap(),
                cuZnImpurity = "",
                severityS = "",
                motionState = "",
                motionEval = "",
                locationPos = "",
                frequencyO = "",
                detectionMethod = "便携式光谱仪",
                detectionD = "",
                failureConsequence = "",
                riskLevel = "",
                controlMeasure = "",
                activityDescription = "",
                ocrSummary = "",
                inspectorNotes = "",
                photoPath = "",
                customFields = emptyMap(),
                createdAt = now,
                updatedAt = now
            )
            dao.addCustomPartAndIncrementCount(entity, currentTotalParts + 1L, now)
            recordCandidateValue("part_component_group", cleanGroup)
            Result.success(partId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePart(
        equipmentId: String,
        part: ComponentInspection,
        remainingParts: List<ComponentInspection>
    ): Result<Unit> {
        return try {
            val now = System.currentTimeMillis()
            val filtered = remainingParts.filterNot { it.id == part.id }
            val newCompleted = filtered.count {
                it.resolvedStatus == PartStatus.VERIFIED || it.resolvedStatus == PartStatus.NON_METAL
            }.toLong()
            dao.deletePartAndUpdateCounts(
                equipmentId = equipmentId,
                partId = part.id,
                newTotalParts = filtered.size.toLong(),
                newCompletedParts = newCompleted,
                updatedAt = now
            )
            PhotoStorageHelper.deletePhotoIfOwned(part.photoPath)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePartPhoto(partId: String, photoPath: String): Result<Unit> {
        return try {
            require(partId.isNotBlank() && photoPath.isNotBlank())
            dao.updatePartPhotoPath(partId, photoPath, System.currentTimeMillis())
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePartInspection(
        equipmentId: String,
        updatedPart: ComponentInspection,
        allPartsForEquipment: List<ComponentInspection>,
        allowedElementSymbols: List<String>
    ): Result<Unit> {
        return try {
            require(equipmentId.isNotBlank() && updatedPart.id.isNotBlank())
            val now = System.currentTimeMillis()
            val allowedSet = allowedElementSymbols.toSet()
            val sanitizedElements = updatedPart.elements
                .filterKeys { it in allowedSet || it.isNotBlank() }
                .mapValues { (_, v) -> v.coerceIn(0.0, 100.0) }
            val sanitizedNdElements = updatedPart.ndElements
                .filter { it !in sanitizedElements && (it in allowedSet || it.isNotBlank()) }
                .toSet()

            val mergedCompletedCount = allPartsForEquipment.count { existingPart ->
                val status = if (existingPart.id == updatedPart.id) {
                    updatedPart.resolvedStatus
                } else {
                    existingPart.resolvedStatus
                }
                status == PartStatus.VERIFIED || status == PartStatus.NON_METAL
            }.toLong()

            val cleanedPart = updatedPart.copy(
                equipmentId = equipmentId,
                elements = sanitizedElements,
                ndElements = sanitizedNdElements,
                updatedAt = now
            )

            dao.updatePartAndProgress(
                part = ComponentPartEntity.fromDomain(cleanedPart),
                completedParts = mergedCompletedCount,
                updatedAt = now
            )

            // Save repeatable waterfall candidates for part fields & custom fields
            recordCandidateValue(TemplateSchemaConfig.KEY_PART_GRADE, cleanedPart.detectedGrade)
            recordCandidateValue(TemplateSchemaConfig.KEY_PART_MOTION_STATE, cleanedPart.motionState)
            recordCandidateValue(TemplateSchemaConfig.KEY_PART_LOCATION_POS, cleanedPart.locationPos)
            recordCandidateValue(TemplateSchemaConfig.KEY_PART_DETECTION_METHOD, cleanedPart.detectionMethod)
            recordCandidateValue(TemplateSchemaConfig.KEY_PART_RISK_LEVEL, cleanedPart.riskLevel)
            cleanedPart.customFields.forEach { (k, v) -> recordCandidateValue(k, v) }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteEquipment(equipmentId: String): Result<Unit> {
        return try {
            val parts = dao.getPartsForEquipmentOnce(equipmentId)
            dao.deleteEquipmentById(equipmentId)
            parts.forEach { PhotoStorageHelper.deletePhotoIfOwned(it.photoPath) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchAllEquipmentsWithParts(): Result<List<Pair<EquipmentRecord, List<ComponentInspection>>>> {
        return try {
            val equipments = dao.getAllEquipmentsOnce().map { it.toDomain() }
            val partsByEq = dao.getAllPartsOnce().map { it.toDomain() }.groupBy { it.equipmentId }
            val result = equipments.map { eq ->
                eq to partsByEq[eq.id].orEmpty().sortedBy { it.sortOrder }
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun rewriteSequencePrefix(code: String, newSequenceNo: Long): String {
        val dotIdx = code.indexOf('.')
        return if (dotIdx > 0) {
            "$newSequenceNo${code.substring(dotIdx)}"
        } else {
            "$newSequenceNo.$code"
        }
    }
}
