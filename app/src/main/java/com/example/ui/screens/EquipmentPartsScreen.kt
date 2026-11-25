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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ComponentInspection
import com.example.model.EquipmentRecord
import com.example.model.PartStatus
import com.example.model.TemplateSchemaConfig
import com.example.model.UiState
import com.example.ui.components.ValveDiagramCard
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentPartsScreen(
    equipment: EquipmentRecord?,
    partsState: UiState<List<ComponentInspection>>,
    templateConfig: TemplateSchemaConfig,
    candidatesByField: Map<String, List<String>>,
    isSaving: Boolean,
    onBack: () -> Unit,
    onOpenPart: (ComponentInspection) -> Unit,
    onAddCustomPart: (codeNumber: String, componentGroup: String, partName: String, quantity: String, systemName: String) -> Unit,
    onDeletePart: (ComponentInspection, List<ComponentInspection>) -> Unit
) {
    var statusFilter by remember { mutableStateOf<PartStatus?>(null) }
    var showAddPartDialog by remember { mutableStateOf(false) }
    var deletePartTarget by remember { mutableStateOf<ComponentInspection?>(null) }

    val allParts = (partsState as? UiState.Success)?.data.orEmpty()
    val filteredParts = remember(allParts, statusFilter) {
        if (statusFilter == null) allParts else allParts.filter { it.resolvedStatus == statusFilter }
    }
    val groupedParts = remember(filteredParts) {
        filteredParts.groupBy { it.componentGroup }
    }
    val nextPendingPart = remember(allParts) {
        allParts.firstOrNull { it.resolvedStatus == PartStatus.PENDING } ?: allParts.firstOrNull()
    }
    val activeSymbols = remember(templateConfig) { templateConfig.activeElementSymbols() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = equipment?.tagNumber ?: "设备部件列表",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (equipment != null) {
                            Text(
                                text = listOfNotNull(
                                    equipment.equipmentName,
                                    equipment.specModel.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("parts_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回设备列表")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showAddPartDialog = true },
                        modifier = Modifier.testTag("add_custom_part_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新增部位")
                    }
                }
            )
        },
        floatingActionButton = {
            if (nextPendingPart != null) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenPart(nextPendingPart) },
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = {
                        val pendingCount = allParts.count { it.resolvedStatus == PartStatus.PENDING }
                        Text(if (pendingCount > 0) "连续检测 (${nextPendingPart.partName})" else "复核首项")
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("start_continuous_inspection_fab")
                )
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
            if (equipment != null) {
                item {
                    val doneCount = allParts.count { it.resolvedStatus != PartStatus.PENDING }
                    val totalCount = allParts.size
                    val progress = if (totalCount > 0) doneCount.toFloat() / totalCount.toFloat() else 0f

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "系统：${equipment.systemName.ifBlank { "未指定" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "已确认 $doneCount / $totalCount 项",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = statusFilter == null,
                                    onClick = { statusFilter = null },
                                    label = { Text("全部 ($totalCount)") }
                                )
                                FilterChip(
                                    selected = statusFilter == PartStatus.PENDING,
                                    onClick = { statusFilter = PartStatus.PENDING },
                                    label = { Text("待测 (${allParts.count { it.resolvedStatus == PartStatus.PENDING }})") }
                                )
                                FilterChip(
                                    selected = statusFilter == PartStatus.VERIFIED,
                                    onClick = { statusFilter = PartStatus.VERIFIED },
                                    label = { Text("已测 (${allParts.count { it.resolvedStatus == PartStatus.VERIFIED }})") }
                                )
                            }
                        }
                    }
                }
            }

            if (allParts.any { it.calloutNumber in 1L..13L }) {
                item {
                    ValveDiagramCard(
                        parts = allParts,
                        onCalloutPartSelected = onOpenPart
                    )
                }
            }

            when (partsState) {
                is UiState.Loading -> {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
                is UiState.Error -> {
                    item {
                        Text(
                            text = partsState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                is UiState.Success -> {
                    groupedParts.forEach { (groupName, groupItems) ->
                        item(key = "header_$groupName") {
                            Text(
                                text = "$groupName (${groupItems.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        items(groupItems, key = { it.id }) { part ->
                            PartRowCard(
                                part = part,
                                activeSymbols = activeSymbols,
                                onClick = { onOpenPart(part) },
                                onDeleteClick = { deletePartTarget = part }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showAddPartDialog && equipment != null) {
        val nextOrder = (allParts.maxOfOrNull { it.sortOrder } ?: 0L) + 1L
        AddPartDialog(
            defaultCodeNumber = "${equipment.sequenceNo}.$nextOrder",
            defaultSystem = equipment.systemName,
            candidatesByField = candidatesByField,
            isSaving = isSaving,
            onDismiss = { showAddPartDialog = false },
            onConfirm = { code, group, name, qty, sys ->
                onAddCustomPart(code, group, name, qty, sys)
                showAddPartDialog = false
            }
        )
    }

    deletePartTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deletePartTarget = null },
            title = { Text("删除检测部位？") },
            text = { Text("将移除【${target.partName}】及其留存的光谱记录与照片。") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeletePart(target, allParts)
                        deletePartTarget = null
                    }
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePartTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun PartRowCard(
    part: ComponentInspection,
    activeSymbols: List<String>,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    val status = part.resolvedStatus
    val statusColor = when (status) {
        PartStatus.VERIFIED -> MaterialTheme.colorScheme.secondary
        PartStatus.NON_METAL -> MaterialTheme.colorScheme.onSurfaceVariant
        PartStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    }
    val hasPhoto = remember(part.photoPath) {
        part.photoPath.isNotBlank() && File(part.photoPath).exists()
    }

    val topElementsText = remember(part.elements, part.ndElements, status, activeSymbols) {
        if (status == PartStatus.NON_METAL) {
            "非金属 · CuZn 0%"
        } else {
            val active = activeSymbols.mapNotNull { sym ->
                val v = part.elements[sym]
                when {
                    v != null && v > 0.0 -> "$sym ${String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')}%"
                    sym in part.ndElements -> "$sym ND"
                    else -> null
                }
            }
            if (active.isEmpty()) "点击离线拍照识别光谱结果"
            else active.take(4).joinToString(" · ")
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("part_card_${part.partName}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (part.calloutNumber > 0L) {
                    Text(
                        text = part.calloutNumber.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = if (status == PartStatus.PENDING) Icons.Default.RadioButtonUnchecked else Icons.Default.CheckCircle,
                        contentDescription = status.label,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = part.codeNumber,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = part.partName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "×${part.quantity}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hasPhoto) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "已留存照片",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                if (part.detectedGrade.isNotBlank()) {
                    Text(
                        text = "牌号/材质：${part.detectedGrade}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = topElementsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "删除部位",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(18.dp)
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入检测",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
