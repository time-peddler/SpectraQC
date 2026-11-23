package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.EquipmentRecord
import com.example.model.EquipmentTemplateType
import com.example.model.InspectionReportMeta
import com.example.model.SectionType
import com.example.model.TemplateSchemaConfig
import com.example.model.UiState
import com.example.ui.theme.CobaltNavy
import com.example.ui.theme.CobaltNavyLight

@Composable
fun HomeEquipmentListScreen(
    metaState: UiState<InspectionReportMeta>,
    equipmentsState: UiState<List<EquipmentRecord>>,
    templateConfig: TemplateSchemaConfig,
    candidatesByField: Map<String, List<String>>,
    isSaving: Boolean,
    onOpenEquipment: (EquipmentRecord) -> Unit,
    onOpenTemplateMaintenance: () -> Unit,
    onSaveMeta: (InspectionReportMeta, () -> Unit) -> Unit,
    onDeleteCandidate: (fieldKey: String, value: String) -> Unit,
    onCreateEquipment: (
        sequenceNo: Long,
        tagNumber: String,
        equipmentName: String,
        specModel: String,
        systemName: String,
        sectionType: SectionType,
        workshopLocation: String,
        infraType: String,
        templateType: EquipmentTemplateType,
        customFields: Map<String, String>,
        onCreated: (String) -> Unit
    ) -> Unit,
    onDuplicateEquipment: (EquipmentRecord, Long, String, String, (String) -> Unit) -> Unit,
    onDeleteEquipment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRegisterDialog by remember { mutableStateOf(false) }
    var showEditMetaDialog by remember { mutableStateOf(false) }
    var duplicateTarget by remember { mutableStateOf<EquipmentRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<EquipmentRecord?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedSectionFilter by remember { mutableStateOf<SectionType?>(null) }

    val currentMeta = (metaState as? UiState.Success)?.data ?: InspectionReportMeta()
    val equipmentsList = (equipmentsState as? UiState.Success)?.data.orEmpty()
    val nextSequenceNo = (equipmentsList.maxOfOrNull { it.sequenceNo } ?: 0L) + 1L

    val filteredEquipments = remember(equipmentsList, searchQuery, selectedSectionFilter) {
        equipmentsList.filter { eq ->
            val matchesSection = selectedSectionFilter == null || eq.resolvedSectionType == selectedSectionFilter
            val q = searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                eq.tagNumber.contains(q, ignoreCase = true) ||
                eq.equipmentName.contains(q, ignoreCase = true) ||
                eq.systemName.contains(q, ignoreCase = true) ||
                eq.specModel.contains(q, ignoreCase = true) ||
                eq.customFields.values.any { it.contains(q, ignoreCase = true) }
            matchesSection && matchesQuery
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showRegisterDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_equipment_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "登记新设备")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ProcessHeaderCard(
                    meta = currentMeta,
                    equipments = equipmentsList,
                    templateConfig = templateConfig,
                    onEditMeta = { showEditMetaDialog = true },
                    onOpenTemplateMaintenance = onOpenTemplateMaintenance
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜索位号、设备名称、规格或系统...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedSectionFilter == null,
                            onClick = { selectedSectionFilter = null },
                            label = { Text("全部 (${equipmentsList.size})") }
                        )
                        FilterChip(
                            selected = selectedSectionFilter == SectionType.EQUIPMENT,
                            onClick = { selectedSectionFilter = SectionType.EQUIPMENT },
                            label = {
                                Text("设备&部件 (${equipmentsList.count { it.resolvedSectionType == SectionType.EQUIPMENT }})")
                            }
                        )
                        FilterChip(
                            selected = selectedSectionFilter == SectionType.INFRASTRUCTURE,
                            onClick = { selectedSectionFilter = SectionType.INFRASTRUCTURE },
                            label = {
                                Text("车间基建 (${equipmentsList.count { it.resolvedSectionType == SectionType.INFRASTRUCTURE }})")
                            }
                        )
                    }
                }
            }

            when (equipmentsState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is UiState.Error -> {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = equipmentsState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
                is UiState.Success -> {
                    if (filteredEquipments.isEmpty()) {
                        item {
                            EmptyEquipmentCard(
                                hasAnyEquipments = equipmentsList.isNotEmpty(),
                                onRegisterClick = { showRegisterDialog = true }
                            )
                        }
                    } else {
                        items(filteredEquipments, key = { it.id }) { equipment ->
                            EquipmentListCard(
                                equipment = equipment,
                                templateConfig = templateConfig,
                                onClick = { onOpenEquipment(equipment) },
                                onDuplicate = { duplicateTarget = equipment },
                                onDelete = { deleteTarget = equipment }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }

    if (showRegisterDialog) {
        RegisterEquipmentDialog(
            nextSequenceNo = nextSequenceNo,
            templateConfig = templateConfig,
            candidatesByField = candidatesByField,
            isSaving = isSaving,
            onDeleteCandidate = onDeleteCandidate,
            onDismiss = { showRegisterDialog = false },
            onConfirm = { seq, tag, name, spec, sys, sec, loc, infra, tpl, custom ->
                onCreateEquipment(seq, tag, name, spec, sys, sec, loc, infra, tpl, custom) { _ ->
                    showRegisterDialog = false
                }
            }
        )
    }

    if (showEditMetaDialog) {
        EditMetaDialog(
            initialMeta = currentMeta,
            isSaving = isSaving,
            onDismiss = { showEditMetaDialog = false },
            onConfirm = { updated ->
                onSaveMeta(updated) {
                    showEditMetaDialog = false
                }
            }
        )
    }

    duplicateTarget?.let { src ->
        DuplicateEquipmentDialog(
            source = src,
            nextSequenceNo = nextSequenceNo,
            candidatesByField = candidatesByField,
            isSaving = isSaving,
            onDismiss = { duplicateTarget = null },
            onConfirm = { seq, newTag, newSpec ->
                onDuplicateEquipment(src, seq, newTag, newSpec) { _ ->
                    duplicateTarget = null
                }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("确认删除设备记录？") },
            text = { Text("将永久删除【${target.tagNumber} ${target.equipmentName}】及其所有零部件光谱记录与留存照片。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteEquipment(target.id)
                        deleteTarget = null
                    }
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ProcessHeaderCard(
    meta: InspectionReportMeta,
    equipments: List<EquipmentRecord>,
    templateConfig: TemplateSchemaConfig,
    onEditMeta: () -> Unit,
    onOpenTemplateMaintenance: () -> Unit
) {
    val totalParts = equipments.sumOf { it.totalParts }
    val completedParts = equipments.sumOf { it.completedParts }
    val progress = if (totalParts > 0) completedParts.toFloat() / totalParts.toFloat() else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(CobaltNavy, CobaltNavyLight)))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "工序：${meta.processName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF7DD3FC)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = meta.docTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${meta.docCode} · ${templateConfig.activeElementSymbols().size} 项元素列",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
                Row {
                    IconButton(
                        onClick = onOpenTemplateMaintenance,
                        modifier = Modifier.testTag("open_template_maintenance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "模板字段维护",
                            tint = Color.White
                        )
                    }
                    IconButton(
                        onClick = onEditMeta,
                        modifier = Modifier.testTag("edit_meta_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑工序与文件信息",
                            tint = Color.White
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KpiPill(
                    label = "已登记设备",
                    value = "${equipments.size} 台",
                    modifier = Modifier.weight(1f)
                )
                KpiPill(
                    label = "零件进度",
                    value = "$completedParts / $totalParts",
                    modifier = Modifier.weight(1f)
                )
                KpiPill(
                    label = "完成率",
                    value = "${(progress * 100).toInt()}%",
                    modifier = Modifier.weight(0.85f)
                )
            }
        }
    }
}

@Composable
private fun KpiPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.11f),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EquipmentListCard(
    equipment: EquipmentRecord,
    templateConfig: TemplateSchemaConfig,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (equipment.totalParts > 0) {
        equipment.completedParts.toFloat() / equipment.totalParts.toFloat()
    } else 0f
    val isAllDone = equipment.totalParts > 0 && equipment.completedParts >= equipment.totalParts

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("equipment_card_${equipment.tagNumber}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "#${equipment.sequenceNo}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Text(
                        text = equipment.tagNumber,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAllDone) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Text(
                        text = "${equipment.completedParts}/${equipment.totalParts} 部件",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isAllDone) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = equipment.equipmentName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val customSummary = templateConfig.activeCustomEquipmentFields().mapNotNull { field ->
                equipment.customFields[field.key]?.takeIf { it.isNotBlank() }?.let { "${field.label}:$it" }
            }
            val metaLine = (
                listOfNotNull(
                    equipment.specModel.takeIf { it.isNotBlank() },
                    equipment.systemName.takeIf { it.isNotBlank() }?.let { "系统:$it" },
                    equipment.workshopLocation.takeIf { it.isNotBlank() }
                ) + customSummary
                ).joinToString(" · ")
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (isAllDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDuplicate) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制设备",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("复制同型")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "删除设备",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "进入部件采集",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyEquipmentCard(
    hasAnyEquipments: Boolean,
    onRegisterClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PrecisionManufacturing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = if (hasAnyEquipments) "没有匹配的设备记录" else "尚未登记任何质检设备",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasAnyEquipments) {
                    "请尝试修改搜索关键词或切换分类筛选。"
                } else {
                    "点击下方按钮登记第一台设备（如 11XV-11272A 切断O型球阀），纯离线自动展开 23 项标准零件供逐一打光谱拍照识别。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hasAnyEquipments) {
                Button(
                    onClick = onRegisterClick,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("empty_register_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("立即登记设备")
                }
            }
        }
    }
}
