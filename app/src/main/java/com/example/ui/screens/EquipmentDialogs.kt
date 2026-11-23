package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.DefaultFieldCandidates
import com.example.model.EquipmentRecord
import com.example.model.EquipmentTemplateType
import com.example.model.InspectionReportMeta
import com.example.model.SectionType
import com.example.model.TemplateSchemaConfig
import com.example.ui.components.WaterfallAutocompleteField

@Composable
fun RegisterEquipmentDialog(
    nextSequenceNo: Long,
    templateConfig: TemplateSchemaConfig,
    candidatesByField: Map<String, List<String>>,
    isSaving: Boolean,
    onDeleteCandidate: (fieldKey: String, value: String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (
        sequenceNo: Long,
        tagNumber: String,
        equipmentName: String,
        specModel: String,
        systemName: String,
        sectionType: SectionType,
        workshopLocation: String,
        infraType: String,
        templateType: EquipmentTemplateType,
        customFields: Map<String, String>
    ) -> Unit
) {
    var sequenceStr by remember { mutableStateOf(nextSequenceNo.toString()) }
    var tagNumber by remember { mutableStateOf("") }
    var equipmentName by remember { mutableStateOf("切断O型球阀") }
    var specModel by remember { mutableStateOf("") }
    var systemName by remember { mutableStateOf("冶铁") }
    var workshopLocation by remember { mutableStateOf("") }
    var infraType by remember { mutableStateOf("") }
    var templateType by remember { mutableStateOf(EquipmentTemplateType.BALL_VALVE_O) }

    val customValues = remember { mutableStateMapOf<String, String>() }
    val showSpecField = templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_EQ_SPEC)
    val showSystemField = templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_EQ_SYSTEM)
    val customEqFields = remember(templateConfig) { templateConfig.activeCustomEquipmentFields() }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                text = "登记新设备 / 检测单元",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. 选择部件模板",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EquipmentTemplateType.entries.forEach { option ->
                        val selected = templateType == option
                        Surface(
                            onClick = {
                                templateType = option
                                if (option == EquipmentTemplateType.WORKSHOP_INFRA && equipmentName == "切断O型球阀") {
                                    equipmentName = "车间单元基建"
                                } else if (option == EquipmentTemplateType.BALL_VALVE_O && equipmentName == "车间单元基建") {
                                    equipmentName = "切断O型球阀"
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = option.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = option.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "2. 填写基础信息（支持输入瀑布下拉候选）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sequenceStr,
                        onValueChange = { sequenceStr = it.filter { ch -> ch.isDigit() } },
                        label = { Text("序号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.32f)
                    )
                    OutlinedTextField(
                        value = tagNumber,
                        onValueChange = { tagNumber = it },
                        label = {
                            Text(
                                if (templateType == EquipmentTemplateType.WORKSHOP_INFRA) "单元/位号*" else "${templateConfig.labelFor(TemplateSchemaConfig.KEY_EQ_TAG, "设备位号")}*"
                            )
                        },
                        placeholder = { Text("如 11XV-11272A") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(0.68f)
                            .testTag("input_tag_number")
                    )
                }

                WaterfallAutocompleteField(
                    value = equipmentName,
                    onValueChange = { equipmentName = it },
                    label = "${templateConfig.labelFor(TemplateSchemaConfig.KEY_EQ_NAME, "设备名称")}*",
                    candidates = candidatesByField[TemplateSchemaConfig.KEY_EQ_NAME].orEmpty(),
                    onDeleteCandidate = { v -> onDeleteCandidate(TemplateSchemaConfig.KEY_EQ_NAME, v) },
                    testTagName = "input_equipment_name"
                )

                if (showSpecField) {
                    WaterfallAutocompleteField(
                        value = specModel,
                        onValueChange = { specModel = it },
                        label = templateConfig.labelFor(TemplateSchemaConfig.KEY_EQ_SPEC, "设备类型及规格"),
                        placeholder = "输入型号、DN、PN 关键字过滤候选或点击拼接",
                        candidates = candidatesByField[TemplateSchemaConfig.KEY_EQ_SPEC].orEmpty(),
                        allowAppend = true,
                        onDeleteCandidate = { v -> onDeleteCandidate(TemplateSchemaConfig.KEY_EQ_SPEC, v) },
                        testTagName = "input_spec_model"
                    )
                }

                if (showSystemField) {
                    WaterfallAutocompleteField(
                        value = systemName,
                        onValueChange = { systemName = it },
                        label = templateConfig.labelFor(TemplateSchemaConfig.KEY_EQ_SYSTEM, "所属系统"),
                        candidates = candidatesByField[TemplateSchemaConfig.KEY_EQ_SYSTEM].orEmpty(),
                        onDeleteCandidate = { v -> onDeleteCandidate(TemplateSchemaConfig.KEY_EQ_SYSTEM, v) },
                        testTagName = "input_system_name"
                    )
                }

                // Dynamically rendered user-maintained custom equipment fields
                customEqFields.forEach { field ->
                    val currentVal = customValues[field.key].orEmpty()
                    val fieldCandidates = (
                        candidatesByField[field.key].orEmpty() +
                            DefaultFieldCandidates.suggestionsForCustomLabel(field.label)
                        ).distinct()

                    if (field.enableWaterfallCandidates) {
                        WaterfallAutocompleteField(
                            value = currentVal,
                            onValueChange = { customValues[field.key] = it },
                            label = field.label,
                            candidates = fieldCandidates,
                            onDeleteCandidate = { v -> onDeleteCandidate(field.key, v) }
                        )
                    } else {
                        OutlinedTextField(
                            value = currentVal,
                            onValueChange = { customValues[field.key] = it },
                            label = { Text(field.label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (templateType == EquipmentTemplateType.WORKSHOP_INFRA) {
                    OutlinedTextField(
                        value = workshopLocation,
                        onValueChange = { workshopLocation = it },
                        label = { Text("车间位置 (第四部分)") },
                        placeholder = { Text("如 炼铁二车间二层平台") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = infraType,
                        onValueChange = { infraType = it },
                        label = { Text("基建类型") },
                        placeholder = { Text("如 工艺管廊/钢结构") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val seq = sequenceStr.toLongOrNull()?.coerceAtLeast(1L) ?: nextSequenceNo
                    onConfirm(
                        seq,
                        tagNumber,
                        equipmentName,
                        specModel,
                        systemName,
                        templateType.defaultSection,
                        workshopLocation,
                        infraType,
                        templateType,
                        customValues.toMap()
                    )
                },
                enabled = !isSaving && tagNumber.isNotBlank() && equipmentName.isNotBlank(),
                modifier = Modifier.testTag("confirm_register_equipment")
            ) {
                Text(if (isSaving) "创建中..." else "登记并生成部件")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("取消")
            }
        }
    )
}

@Composable
fun DuplicateEquipmentDialog(
    source: EquipmentRecord,
    nextSequenceNo: Long,
    candidatesByField: Map<String, List<String>>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (newSequenceNo: Long, newTagNumber: String, newSpecModel: String) -> Unit
) {
    val suggestedTag = remember(source.tagNumber) {
        if (source.tagNumber.endsWith("A")) {
            source.tagNumber.dropLast(1) + "B"
        } else {
            source.tagNumber + "-2"
        }
    }
    var seqStr by remember { mutableStateOf(nextSequenceNo.toString()) }
    var newTag by remember { mutableStateOf(suggestedTag) }
    var newSpec by remember { mutableStateOf(source.specModel) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("快速复制同型设备") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "将复制【${source.tagNumber} ${source.equipmentName}】的全部 ${source.totalParts} 个部位清单，光谱数值重置为待检测，方便连续批量录入。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = seqStr,
                    onValueChange = { seqStr = it.filter { c -> c.isDigit() } },
                    label = { Text("新设备序号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("新设备位号*") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                WaterfallAutocompleteField(
                    value = newSpec,
                    onValueChange = { newSpec = it },
                    label = "设备类型及规格",
                    candidates = candidatesByField[TemplateSchemaConfig.KEY_EQ_SPEC].orEmpty(),
                    allowAppend = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val seq = seqStr.toLongOrNull()?.coerceAtLeast(1L) ?: nextSequenceNo
                    onConfirm(seq, newTag, newSpec)
                },
                enabled = !isSaving && newTag.isNotBlank()
            ) {
                Text(if (isSaving) "复制中..." else "确认复制")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("取消")
            }
        }
    )
}

@Composable
fun EditMetaDialog(
    initialMeta: InspectionReportMeta,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (InspectionReportMeta) -> Unit
) {
    var processName by remember { mutableStateOf(initialMeta.processName) }
    var docTitle by remember { mutableStateOf(initialMeta.docTitle) }
    var docCode by remember { mutableStateOf(initialMeta.docCode) }
    var version by remember { mutableStateOf(initialMeta.version) }
    var securityLevel by remember { mutableStateOf(initialMeta.securityLevel) }
    var approver by remember { mutableStateOf(initialMeta.approver) }
    var effectiveDate by remember { mutableStateOf(initialMeta.effectiveDate) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("编辑模板表头信息") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = processName,
                    onValueChange = { processName = it },
                    label = { Text("工序") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = docTitle,
                    onValueChange = { docTitle = it },
                    label = { Text("文件名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = docCode,
                        onValueChange = { docCode = it },
                        label = { Text("文件编号") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = version,
                        onValueChange = { version = it },
                        label = { Text("版本") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = securityLevel,
                        onValueChange = { securityLevel = it },
                        label = { Text("保密等级") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = approver,
                        onValueChange = { approver = it },
                        label = { Text("批准人") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = effectiveDate,
                    onValueChange = { effectiveDate = it },
                    label = { Text("生效日期") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        initialMeta.copy(
                            processName = processName,
                            docTitle = docTitle,
                            docCode = docCode,
                            version = version,
                            securityLevel = securityLevel,
                            approver = approver,
                            effectiveDate = effectiveDate
                        )
                    )
                },
                enabled = !isSaving && processName.isNotBlank() && docTitle.isNotBlank()
            ) {
                Text(if (isSaving) "保存中..." else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("取消")
            }
        }
    )
}

@Composable
fun AddPartDialog(
    defaultCodeNumber: String,
    defaultSystem: String,
    candidatesByField: Map<String, List<String>>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (codeNumber: String, componentGroup: String, partName: String, quantity: String, systemName: String) -> Unit
) {
    var codeNumber by remember { mutableStateOf(defaultCodeNumber) }
    var componentGroup by remember { mutableStateOf("阀体") }
    var partName by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var systemName by remember { mutableStateOf(defaultSystem) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("新增检测部位") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = codeNumber,
                        onValueChange = { codeNumber = it },
                        label = { Text("编号") },
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                    OutlinedTextField(
                        value = quantity,
                        onValueChange = { quantity = it },
                        label = { Text("数量") },
                        singleLine = true,
                        modifier = Modifier.weight(0.5f)
                    )
                }
                WaterfallAutocompleteField(
                    value = componentGroup,
                    onValueChange = { componentGroup = it },
                    label = "部件名称",
                    candidates = candidatesByField["part_component_group"].orEmpty()
                )
                OutlinedTextField(
                    value = partName,
                    onValueChange = { partName = it },
                    label = { Text("零件名称*") },
                    placeholder = { Text("如 法兰盖 / 垫片 / 轴承") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                WaterfallAutocompleteField(
                    value = systemName,
                    onValueChange = { systemName = it },
                    label = "所属系统",
                    candidates = candidatesByField[TemplateSchemaConfig.KEY_EQ_SYSTEM].orEmpty()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(codeNumber, componentGroup, partName, quantity, systemName) },
                enabled = !isSaving && partName.isNotBlank()
            ) {
                Text(if (isSaving) "添加中..." else "添加部位")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("取消")
            }
        }
    )
}
