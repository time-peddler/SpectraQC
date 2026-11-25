package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.model.ComponentInspection
import com.example.model.EquipmentRecord
import com.example.model.InspectionReportMeta
import org.json.JSONObject

class ElementMapConverter {
    @TypeConverter
    fun fromMap(map: Map<String, Double>): String {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    @TypeConverter
    fun toMap(raw: String): Map<String, Double> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, Double>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val num = json.optDouble(key, Double.NaN)
                if (!num.isNaN()) {
                    result[key] = num
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromStringMap(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        return json.toString()
    }

    @TypeConverter
    fun toStringMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = linkedMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = json.optString(key, "")
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromStringSet(set: Set<String>): String {
        return set.filter { it.isNotBlank() }.joinToString(",")
    }

    @TypeConverter
    fun toStringSet(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

@Entity(tableName = "report_meta")
data class ReportMetaEntity(
    @PrimaryKey val id: String = "default",
    val processName: String,
    val docTitle: String,
    val docCode: String,
    val version: String,
    val securityLevel: String,
    val approver: String,
    val effectiveDate: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): InspectionReportMeta = InspectionReportMeta(
        id = id,
        processName = processName,
        docTitle = docTitle,
        docCode = docCode,
        version = version,
        securityLevel = securityLevel,
        approver = approver,
        effectiveDate = effectiveDate,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(meta: InspectionReportMeta): ReportMetaEntity = ReportMetaEntity(
            id = meta.id.ifBlank { "default" },
            processName = meta.processName,
            docTitle = meta.docTitle,
            docCode = meta.docCode,
            version = meta.version,
            securityLevel = meta.securityLevel,
            approver = meta.approver,
            effectiveDate = meta.effectiveDate,
            createdAt = meta.createdAt,
            updatedAt = meta.updatedAt
        )
    }
}

@Entity(tableName = "template_config")
data class TemplateConfigEntity(
    @PrimaryKey val id: String = "default",
    val fieldsJson: String,
    val updatedAt: Long
)

@Entity(
    tableName = "field_candidates",
    indices = [Index(value = ["fieldKey", "value"], unique = true)]
)
data class FieldCandidateEntity(
    @PrimaryKey val id: String,
    val fieldKey: String,
    val value: String,
    val useCount: Long,
    val updatedAt: Long
)

@Entity(tableName = "equipments")
data class EquipmentEntity(
    @PrimaryKey val id: String,
    val sequenceNo: Long,
    val tagNumber: String,
    val equipmentName: String,
    val specModel: String,
    val systemName: String,
    val sectionType: String,
    val workshopLocation: String,
    val infraType: String,
    val templateType: String,
    val customFields: Map<String, String>,
    val totalParts: Long,
    val completedParts: Long,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): EquipmentRecord = EquipmentRecord(
        id = id,
        sequenceNo = sequenceNo,
        tagNumber = tagNumber,
        equipmentName = equipmentName,
        specModel = specModel,
        systemName = systemName,
        sectionType = sectionType,
        workshopLocation = workshopLocation,
        infraType = infraType,
        templateType = templateType,
        customFields = customFields,
        totalParts = totalParts,
        completedParts = completedParts,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(eq: EquipmentRecord): EquipmentEntity = EquipmentEntity(
            id = eq.id,
            sequenceNo = eq.sequenceNo,
            tagNumber = eq.tagNumber,
            equipmentName = eq.equipmentName,
            specModel = eq.specModel,
            systemName = eq.systemName,
            sectionType = eq.sectionType,
            workshopLocation = eq.workshopLocation,
            infraType = eq.infraType,
            templateType = eq.templateType,
            customFields = eq.customFields,
            totalParts = eq.totalParts,
            completedParts = eq.completedParts,
            createdAt = eq.createdAt,
            updatedAt = eq.updatedAt
        )
    }
}

@Entity(
    tableName = "component_parts",
    foreignKeys = [
        ForeignKey(
            entity = EquipmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["equipmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["equipmentId"])]
)
data class ComponentPartEntity(
    @PrimaryKey val id: String,
    val equipmentId: String,
    val codeNumber: String,
    val sortOrder: Long,
    val systemName: String,
    val componentGroup: String,
    val partName: String,
    val quantity: String,
    val calloutNumber: Long,
    val status: String,
    val materialType: String,
    val detectedGrade: String,
    val elements: Map<String, Double>,
    val ndElements: Set<String> = emptySet(),
    val cuZnImpurity: String,
    val severityS: String,
    val motionState: String,
    val motionEval: String,
    val locationPos: String,
    val frequencyO: String,
    val detectionMethod: String,
    val detectionD: String,
    val failureConsequence: String,
    val riskLevel: String,
    val controlMeasure: String,
    val activityDescription: String,
    val ocrSummary: String,
    val inspectorNotes: String,
    val photoPath: String,
    val customFields: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): ComponentInspection = ComponentInspection(
        id = id,
        equipmentId = equipmentId,
        codeNumber = codeNumber,
        sortOrder = sortOrder,
        systemName = systemName,
        componentGroup = componentGroup,
        partName = partName,
        quantity = quantity,
        calloutNumber = calloutNumber,
        status = status,
        materialType = materialType,
        detectedGrade = detectedGrade,
        elements = elements,
        ndElements = ndElements,
        cuZnImpurity = cuZnImpurity,
        severityS = severityS,
        motionState = motionState,
        motionEval = motionEval,
        locationPos = locationPos,
        frequencyO = frequencyO,
        detectionMethod = detectionMethod,
        detectionD = detectionD,
        failureConsequence = failureConsequence,
        riskLevel = riskLevel,
        controlMeasure = controlMeasure,
        activityDescription = activityDescription,
        ocrSummary = ocrSummary,
        inspectorNotes = inspectorNotes,
        photoPath = photoPath,
        customFields = customFields,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(part: ComponentInspection): ComponentPartEntity = ComponentPartEntity(
            id = part.id,
            equipmentId = part.equipmentId,
            codeNumber = part.codeNumber,
            sortOrder = part.sortOrder,
            systemName = part.systemName,
            componentGroup = part.componentGroup,
            partName = part.partName,
            quantity = part.quantity,
            calloutNumber = part.calloutNumber,
            status = part.status,
            materialType = part.materialType,
            detectedGrade = part.detectedGrade,
            elements = part.elements,
            ndElements = part.ndElements,
            cuZnImpurity = part.cuZnImpurity,
            severityS = part.severityS,
            motionState = part.motionState,
            motionEval = part.motionEval,
            locationPos = part.locationPos,
            frequencyO = part.frequencyO,
            detectionMethod = part.detectionMethod,
            detectionD = part.detectionD,
            failureConsequence = part.failureConsequence,
            riskLevel = part.riskLevel,
            controlMeasure = part.controlMeasure,
            activityDescription = part.activityDescription,
            ocrSummary = part.ocrSummary,
            inspectorNotes = part.inspectorNotes,
            photoPath = part.photoPath,
            customFields = part.customFields,
            createdAt = part.createdAt,
            updatedAt = part.updatedAt
        )
    }
}
