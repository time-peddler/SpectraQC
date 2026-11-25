package com.example.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Default 17 chemical element columns from customer template Section 3 & 4 */
val ELEMENT_SYMBOLS = listOf(
    "W", "Fe", "Cr", "Ni", "Zn", "Cu", "Mn", "Mo",
    "Ti", "Co", "Pb", "Si", "V", "Sn", "Zr", "Nb", "Al"
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class PartStatus(val label: String) {
    PENDING("待检测"),
    VERIFIED("已保存"),
    NON_METAL("非金属");

    companion object {
        fun fromRaw(raw: String?): PartStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class SectionType(val label: String) {
    EQUIPMENT("设备&部件"),
    INFRASTRUCTURE("车间基建单元");

    companion object {
        fun fromRaw(raw: String?): SectionType =
            entries.firstOrNull { it.name == raw } ?: EQUIPMENT
    }
}

enum class FieldCategory(val label: String) {
    EQUIPMENT_INFO("设备登记字段"),
    ELEMENT("化学元素列"),
    PART_DETAIL("部位检测与评价字段");

    companion object {
        fun fromRaw(raw: String?): FieldCategory =
            entries.firstOrNull { it.name == raw } ?: PART_DETAIL
    }
}

data class TemplateFieldDef(
    val key: String,
    val label: String,
    val category: FieldCategory,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = true,
    val isCoreRequired: Boolean = false,
    val enableWaterfallCandidates: Boolean = false,
    val sortOrder: Int = 0
) {
    val elementSymbol: String
        get() = if (key.startsWith("elem_")) key.removePrefix("elem_") else label.trim()
}

data class TemplateSchemaConfig(
    val fields: List<TemplateFieldDef> = defaultFields()
) {
    fun activeEquipmentFields(): List<TemplateFieldDef> =
        fields.filter { it.category == FieldCategory.EQUIPMENT_INFO && it.enabled }
            .sortedBy { it.sortOrder }

    fun activeCustomEquipmentFields(): List<TemplateFieldDef> =
        fields.filter { it.category == FieldCategory.EQUIPMENT_INFO && !it.isBuiltIn && it.enabled }
            .sortedBy { it.sortOrder }

    fun activeElementFields(): List<TemplateFieldDef> =
        fields.filter { it.category == FieldCategory.ELEMENT && it.enabled }
            .sortedBy { it.sortOrder }

    fun activeElementSymbols(): List<String> =
        activeElementFields().map { it.elementSymbol }.filter { it.isNotBlank() }.distinct()

    fun activePartDetailFields(): List<TemplateFieldDef> =
        fields.filter { it.category == FieldCategory.PART_DETAIL && it.enabled }
            .sortedBy { it.sortOrder }

    fun activeCustomPartDetailFields(): List<TemplateFieldDef> =
        fields.filter { it.category == FieldCategory.PART_DETAIL && !it.isBuiltIn && it.enabled }
            .sortedBy { it.sortOrder }

    fun isFieldEnabled(key: String): Boolean =
        fields.firstOrNull { it.key == key }?.enabled ?: true

    fun labelFor(key: String, fallback: String): String =
        fields.firstOrNull { it.key == key }?.label?.ifBlank { fallback } ?: fallback

    fun toJsonString(): String {
        val arr = JSONArray()
        fields.forEach { f ->
            val obj = JSONObject()
            obj.put("key", f.key)
            obj.put("label", f.label)
            obj.put("category", f.category.name)
            obj.put("enabled", f.enabled)
            obj.put("isBuiltIn", f.isBuiltIn)
            obj.put("isCoreRequired", f.isCoreRequired)
            obj.put("enableWaterfallCandidates", f.enableWaterfallCandidates)
            obj.put("sortOrder", f.sortOrder)
            arr.put(obj)
        }
        return arr.toString()
    }

    companion object {
        const val KEY_EQ_TAG = "eq_tag"
        const val KEY_EQ_NAME = "eq_name"
        const val KEY_EQ_SPEC = "eq_spec_model"
        const val KEY_EQ_SYSTEM = "eq_system"

        const val KEY_PART_MATERIAL = "part_material_type"
        const val KEY_PART_CUZN = "part_cuzn"
        const val KEY_PART_GRADE = "part_grade"
        const val KEY_PART_SEVERITY_S = "part_severity_s"
        const val KEY_PART_MOTION_STATE = "part_motion_state"
        const val KEY_PART_MOTION_EVAL = "part_motion_eval"
        const val KEY_PART_LOCATION_POS = "part_location_pos"
        const val KEY_PART_FREQUENCY_O = "part_frequency_o"
        const val KEY_PART_DETECTION_METHOD = "part_detection_method"
        const val KEY_PART_DETECTION_D = "part_detection_d"
        const val KEY_PART_FAILURE_CONSEQUENCE = "part_failure_consequence"
        const val KEY_PART_RISK_LEVEL = "part_risk_level"
        const val KEY_PART_CONTROL_MEASURE = "part_control_measure"
        const val KEY_PART_PHOTO = "part_photo"

        fun defaultFields(): List<TemplateFieldDef> {
            val list = mutableListOf<TemplateFieldDef>()
            var order = 1

            // 1. Built-in Equipment fields (口径/压力 removed per customer table)
            list += TemplateFieldDef(KEY_EQ_TAG, "设备位号", FieldCategory.EQUIPMENT_INFO, true, true, true, true, order++)
            list += TemplateFieldDef(KEY_EQ_NAME, "设备名称", FieldCategory.EQUIPMENT_INFO, true, true, true, true, order++)
            list += TemplateFieldDef(KEY_EQ_SPEC, "设备类型及规格", FieldCategory.EQUIPMENT_INFO, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_EQ_SYSTEM, "所属系统", FieldCategory.EQUIPMENT_INFO, true, true, false, true, order++)

            // 2. Built-in 17 Chemical Elements
            ELEMENT_SYMBOLS.forEach { sym ->
                list += TemplateFieldDef(
                    key = "elem_$sym",
                    label = sym,
                    category = FieldCategory.ELEMENT,
                    enabled = true,
                    isBuiltIn = true,
                    isCoreRequired = false,
                    enableWaterfallCandidates = false,
                    sortOrder = order++
                )
            }

            // 3. Built-in Part Detail, Risk & Photo fields
            list += TemplateFieldDef(KEY_PART_MATERIAL, "材质类型", FieldCategory.PART_DETAIL, true, true, true, false, order++)
            list += TemplateFieldDef(KEY_PART_CUZN, "CuZn杂质含量", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_GRADE, "识别牌号", FieldCategory.PART_DETAIL, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_PART_SEVERITY_S, "严重度(S)", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_MOTION_STATE, "运动状态", FieldCategory.PART_DETAIL, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_PART_MOTION_EVAL, "运动状态评价", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_LOCATION_POS, "所处位置", FieldCategory.PART_DETAIL, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_PART_FREQUENCY_O, "频度(O)", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_DETECTION_METHOD, "探测方法", FieldCategory.PART_DETAIL, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_PART_DETECTION_D, "探测度(D)", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_FAILURE_CONSEQUENCE, "失效后果", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_RISK_LEVEL, "风险等级", FieldCategory.PART_DETAIL, true, true, false, true, order++)
            list += TemplateFieldDef(KEY_PART_CONTROL_MEASURE, "消除/控制措施", FieldCategory.PART_DETAIL, true, true, false, false, order++)
            list += TemplateFieldDef(KEY_PART_PHOTO, "光谱照片", FieldCategory.PART_DETAIL, true, true, false, false, order++)

            return list
        }

        fun fromJsonString(raw: String?): TemplateSchemaConfig {
            if (raw.isNullOrBlank()) return TemplateSchemaConfig()
            return try {
                val arr = JSONArray(raw)
                val parsed = mutableListOf<TemplateFieldDef>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val key = obj.optString("key")
                    val label = obj.optString("label")
                    if (key.isBlank() || label.isBlank()) continue
                    parsed += TemplateFieldDef(
                        key = key,
                        label = label,
                        category = FieldCategory.fromRaw(obj.optString("category")),
                        enabled = obj.optBoolean("enabled", true),
                        isBuiltIn = obj.optBoolean("isBuiltIn", false),
                        isCoreRequired = obj.optBoolean("isCoreRequired", false),
                        enableWaterfallCandidates = obj.optBoolean("enableWaterfallCandidates", false),
                        sortOrder = obj.optInt("sortOrder", i + 1)
                    )
                }
                if (parsed.isEmpty()) TemplateSchemaConfig() else TemplateSchemaConfig(parsed)
            } catch (_: Exception) {
                TemplateSchemaConfig()
            }
        }
    }
}

object DefaultFieldCandidates {
    val builtInByFieldKey: Map<String, List<String>> = mapOf(
        TemplateSchemaConfig.KEY_EQ_SPEC to listOf(
            "MODEL500S10/W-OAS-WXC",
            "Q41F-16P",
            "Q641F-16C",
            "Z941H-16C",
            "DN50 PN16",
            "DN80 PN16",
            "DN100 PN16",
            "DN150 PN16",
            "DN200 PN25",
            "DN250 PN16",
            "PN16",
            "PN25",
            "PN40",
            "Class150",
            "Class300"
        ),
        TemplateSchemaConfig.KEY_EQ_NAME to listOf(
            "切断O型球阀",
            "气动调节阀",
            "电动闸阀",
            "气动蝶阀",
            "止回阀",
            "截止阀",
            "车间工艺管线"
        ),
        TemplateSchemaConfig.KEY_EQ_SYSTEM to listOf(
            "冶铁",
            "炼钢",
            "煤气",
            "循环水",
            "蒸汽",
            "氮气",
            "压缩空气"
        ),
        TemplateSchemaConfig.KEY_PART_GRADE to listOf(
            "SS304",
            "316L",
            "321",
            "WCB",
            "20#",
            "Q235B",
            "45#",
            "35CrMo",
            "2205",
            "CF8",
            "CF8M",
            "PTFE"
        ),
        TemplateSchemaConfig.KEY_PART_MOTION_STATE to listOf("静止", "旋转", "往复", "微动"),
        TemplateSchemaConfig.KEY_PART_LOCATION_POS to listOf("内部承压件", "外部连接件", "密封面", "执行机构"),
        TemplateSchemaConfig.KEY_PART_DETECTION_METHOD to listOf("便携式光谱仪", "直读光谱仪", "目视/材质核查"),
        TemplateSchemaConfig.KEY_PART_RISK_LEVEL to listOf("低风险", "中风险", "高风险"),
        "part_component_group" to listOf("阀体", "附件", "执行机构", "管路系统", "紧固件")
    )

    fun suggestionsForCustomLabel(label: String): List<String> {
        return when {
            label.contains("口径") || label.contains("DN", ignoreCase = true) ->
                listOf("DN25", "DN40", "DN50", "DN65", "DN80", "DN100", "DN150", "DN200", "DN250", "DN300")
            label.contains("压力") || label.contains("PN", ignoreCase = true) ->
                listOf("PN10", "PN16", "PN25", "PN40", "PN64", "Class150", "Class300", "Class600", "1.6MPa", "2.5MPa")
            else -> emptyList()
        }
    }
}

enum class EquipmentTemplateType(
    val displayName: String,
    val description: String,
    val defaultSection: SectionType
) {
    BALL_VALVE_O(
        displayName = "切断O型球阀 (23项标准)",
        description = "含阀体13项解析图部件 + 附件8项 + 执行机构2项",
        defaultSection = SectionType.EQUIPMENT
    ),
    GENERAL_VALVE(
        displayName = "通用工业阀门 (8项)",
        description = "阀体、阀盖、阀杆、阀芯、阀座、填料、紧固螺栓、执行器",
        defaultSection = SectionType.EQUIPMENT
    ),
    WORKSHOP_INFRA(
        displayName = "车间单元基建 (5项)",
        description = "对应模板第四部分：管道、法兰、支架、护栏、钢平台",
        defaultSection = SectionType.INFRASTRUCTURE
    ),
    CUSTOM(
        displayName = "空白自定义设备",
        description = "登记基础位号后自由添加检测部位",
        defaultSection = SectionType.EQUIPMENT
    );

    companion object {
        fun fromRaw(raw: String?): EquipmentTemplateType =
            entries.firstOrNull { it.name == raw } ?: CUSTOM
    }
}

data class InspectionReportMeta(
    val id: String = "default",
    val processName: String = "炼铁工序",
    val docTitle: String = "设备&部件材质成分分析表",
    val docCode: String = "QC-MAT-2026-01",
    val version: String = "A/0",
    val securityLevel: String = "内部受控",
    val approver: String = "",
    val effectiveDate: String = "2026-11-22",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class EquipmentRecord(
    val id: String = "",
    val sequenceNo: Long = 1L,
    val tagNumber: String = "",
    val equipmentName: String = "",
    val specModel: String = "",
    val systemName: String = "",
    val sectionType: String = SectionType.EQUIPMENT.name,
    val workshopLocation: String = "",
    val infraType: String = "",
    val templateType: String = EquipmentTemplateType.BALL_VALVE_O.name,
    val customFields: Map<String, String> = emptyMap(),
    val totalParts: Long = 0L,
    val completedParts: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val resolvedSectionType: SectionType
        get() = SectionType.fromRaw(sectionType)

    val resolvedTemplateType: EquipmentTemplateType
        get() = EquipmentTemplateType.fromRaw(templateType)
}

data class ComponentInspection(
    val id: String = "",
    val equipmentId: String = "",
    val codeNumber: String = "",
    val sortOrder: Long = 0L,
    val systemName: String = "",
    val componentGroup: String = "",
    val partName: String = "",
    val quantity: String = "1",
    val calloutNumber: Long = 0L,
    val status: String = PartStatus.PENDING.name,
    val materialType: String = "金属",
    val detectedGrade: String = "",
    val elements: Map<String, Double> = emptyMap(),
    val ndElements: Set<String> = emptySet(),
    val cuZnImpurity: String = "",
    val severityS: String = "",
    val motionState: String = "",
    val motionEval: String = "",
    val locationPos: String = "",
    val frequencyO: String = "",
    val detectionMethod: String = "便携式光谱仪",
    val detectionD: String = "",
    val failureConsequence: String = "",
    val riskLevel: String = "",
    val controlMeasure: String = "",
    val activityDescription: String = "",
    val ocrSummary: String = "",
    val inspectorNotes: String = "",
    val photoPath: String = "",
    val customFields: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val resolvedStatus: PartStatus
        get() = PartStatus.fromRaw(status)

    fun computeCuZnImpurityDisplay(): String {
        if (resolvedStatus == PartStatus.NON_METAL || materialType == "非金属") return "0%"
        val cu = elements["Cu"] ?: 0.0
        val zn = elements["Zn"] ?: 0.0
        val total = cu + zn
        return when {
            total <= 0.0001 -> "0%"
            kotlin.math.round(total * 1000).toLong() % 10L != 0L -> String.format(Locale.US, "%.3f%%", total)
            else -> String.format(Locale.US, "%.2f%%", total)
        }
    }
}

data class PartSeedSpec(
    val codeSuffix: String,
    val componentGroup: String,
    val partName: String,
    val quantity: String,
    val calloutNumber: Long = 0L,
    val defaultNonMetal: Boolean = false
)

object TemplatePresets {
    fun buildPartsForTemplate(
        templateType: EquipmentTemplateType,
        sequenceNo: Long
    ): List<PartSeedSpec> {
        val prefix = sequenceNo.toString()
        return when (templateType) {
            EquipmentTemplateType.BALL_VALVE_O -> listOf(
                PartSeedSpec("$prefix.1.1", "阀体", "阀腔", "1", 1L),
                PartSeedSpec("$prefix.1.2", "阀体", "阀体", "1", 2L),
                PartSeedSpec("$prefix.1.3", "阀体", "阀芯", "1", 3L),
                PartSeedSpec("$prefix.1.4", "阀体", "阀座", "2", 4L, defaultNonMetal = true),
                PartSeedSpec("$prefix.1.5", "阀体", "阀杆", "1", 5L),
                PartSeedSpec("$prefix.1.6", "阀体", "中线密封垫片", "1", 6L),
                PartSeedSpec("$prefix.1.7", "阀体", "填料压紧螺母", "2", 7L),
                PartSeedSpec("$prefix.1.8", "阀体", "填料压盖", "1", 8L),
                PartSeedSpec("$prefix.1.9", "阀体", "填料压紧螺栓", "2", 9L),
                PartSeedSpec("$prefix.1.10", "阀体", "填料组", "1", 10L, defaultNonMetal = true),
                PartSeedSpec("$prefix.1.11", "阀体", "中线密封螺栓", "*", 11L),
                PartSeedSpec("$prefix.1.12", "阀体", "中线密封螺母", "*", 12L),
                PartSeedSpec("$prefix.1.13", "阀体", "固定螺栓", "4", 13L),
                PartSeedSpec("$prefix.2.1", "附件", "过滤减压阀", "1"),
                PartSeedSpec("$prefix.2.2", "附件", "支架", "1"),
                PartSeedSpec("$prefix.2.3", "附件", "电磁阀(阀头)", "1"),
                PartSeedSpec("$prefix.2.4", "附件", "电磁阀(阀体)", "1"),
                PartSeedSpec("$prefix.2.5", "附件", "限位开关", "1"),
                PartSeedSpec("$prefix.2.6", "附件", "气源管", "1"),
                PartSeedSpec("$prefix.2.7", "附件", "卡套接头", "*"),
                PartSeedSpec("$prefix.2.8", "附件", "球阀及其他附件", "*"),
                PartSeedSpec("$prefix.3.1", "执行机构", "气缸", "1"),
                PartSeedSpec("$prefix.3.2", "执行机构", "铭牌", "1")
            )
            EquipmentTemplateType.GENERAL_VALVE -> listOf(
                PartSeedSpec("$prefix.1.1", "阀体", "阀体", "1"),
                PartSeedSpec("$prefix.1.2", "阀体", "阀盖", "1"),
                PartSeedSpec("$prefix.1.3", "阀体", "阀芯/闸板", "1"),
                PartSeedSpec("$prefix.1.4", "阀体", "阀座", "2"),
                PartSeedSpec("$prefix.1.5", "阀体", "阀杆", "1"),
                PartSeedSpec("$prefix.1.6", "阀体", "填料压盖", "1"),
                PartSeedSpec("$prefix.1.7", "阀体", "连接螺栓螺母", "*"),
                PartSeedSpec("$prefix.2.1", "执行机构", "手轮/执行器", "1")
            )
            EquipmentTemplateType.WORKSHOP_INFRA -> listOf(
                PartSeedSpec("$prefix.1", "管路系统", "主管段/弯头", "1"),
                PartSeedSpec("$prefix.2", "管路系统", "连接法兰", "2"),
                PartSeedSpec("$prefix.3", "支撑结构", "管托/支架", "1"),
                PartSeedSpec("$prefix.4", "紧固件", "法兰螺栓组", "*"),
                PartSeedSpec("$prefix.5", "基建平台", "护栏/钢梯", "1")
            )
            EquipmentTemplateType.CUSTOM -> emptyList()
        }
    }
}
