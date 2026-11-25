package com.example.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.model.ComponentInspection
import com.example.model.EquipmentRecord
import com.example.model.InspectionReportMeta
import com.example.model.PartStatus
import com.example.model.SectionType
import com.example.model.TemplateFieldDef
import com.example.model.TemplateSchemaConfig
import com.example.model.UiState
import com.example.util.ExportedXlsxResult
import com.example.util.ReportExporter
import com.example.util.XLSX_MIME_TYPE
import java.io.File
import java.util.Locale

private data class ReportFlatRow(
    val equipment: EquipmentRecord,
    val isFirstForEquipment: Boolean,
    val part: ComponentInspection
)

@Composable
fun StructuredReportScreen(
    metaState: UiState<InspectionReportMeta>,
    reportDataState: UiState<List<Pair<EquipmentRecord, List<ComponentInspection>>>>,
    templateConfig: TemplateSchemaConfig,
    onRefresh: () -> Unit,
    onOpenTemplateMaintenance: () -> Unit,
    onExportNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var lastExported by remember { mutableStateOf<ExportedXlsxResult?>(null) }
    var previewPhotoFile by remember { mutableStateOf<Pair<String, File>?>(null) }
    val hScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        onRefresh()
    }

    val meta = (metaState as? UiState.Success)?.data ?: InspectionReportMeta()
    val dataItems = (reportDataState as? UiState.Success)?.data.orEmpty()

    val activeEqFields = remember(templateConfig) { templateConfig.activeEquipmentFields() }
    val activeElements = remember(templateConfig) { templateConfig.activeElementSymbols() }
    val activeDetailFields = remember(templateConfig) { templateConfig.activePartDetailFields() }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(XLSX_MIME_TYPE)
    ) { uri ->
        if (uri != null) {
            ReportExporter.writeXlsxToUri(context, uri, meta, dataItems, templateConfig)
                .onSuccess { photoCount ->
                    lastExported = ExportedXlsxResult(
                        uri = uri,
                        fileName = ReportExporter.defaultXlsxFileName(meta),
                        displayLocation = "已保存到您选择的文件夹（内含 $photoCount 张留存照片）",
                        embeddedPhotoCount = photoCount
                    )
                    onExportNotice("已成功导出 .xlsx（含 $photoCount 张照片）")
                }
                .onFailure { e -> onExportNotice("导出失败：${e.localizedMessage}") }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (dataItems.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    saveAsLauncher.launch(ReportExporter.defaultXlsxFileName(meta))
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(0.4f)
                                    .height(48.dp)
                                    .testTag("save_as_xlsx_button")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("另存为")
                            }
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ReportExporter.exportToDownloadsFolder(context, meta, dataItems, templateConfig)
                                            .onSuccess { exported ->
                                                lastExported = exported
                                                onExportNotice("已导出至「下载/SpectraQC」（嵌入 ${exported.embeddedPhotoCount} 张照片）")
                                            }
                                            .onFailure { e ->
                                                onExportNotice("导出失败：${e.localizedMessage}")
                                            }
                                    } else {
                                        saveAsLauncher.launch(ReportExporter.defaultXlsxFileName(meta))
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .weight(0.6f)
                                    .height(48.dp)
                                    .testTag("one_tap_export_xlsx_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("一键导出 XLSX 表格")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "结构化材质成分报表 (.xlsx)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "工序：${meta.processName} · 已按模板维护配置 ${activeElements.size} 元素列",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onOpenTemplateMaintenance) {
                        Icon(Icons.Default.Tune, contentDescription = "模板字段维护")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新报表")
                    }
                }
            }

            lastExported?.let { exported ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "XLSX 已就绪（已插入 ${exported.embeddedPhotoCount} 张照片）",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = exported.displayLocation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(ReportExporter.createViewXlsxIntent(exported.uri))
                                    } catch (_: Exception) {
                                        onExportNotice("未找到可打开 .xlsx 的应用，请打开手机文件管理器查看 Download/SpectraQC")
                                    }
                                }
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("打开查看")
                            }
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        ReportExporter.createShareXlsxIntent(
                                            exported.uri,
                                            "${meta.processName}_${meta.docTitle}"
                                        )
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("分享文件")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("三、设备&部件材质表") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("四、车间基建成分表") }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("二、活动描述汇总") }
                )
            }

            when (reportDataState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = reportDataState.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
                is UiState.Success -> {
                    if (selectedTab == 2) {
                        val activityItems = dataItems.flatMap { (eq, parts) ->
                            parts.filter { it.activityDescription.isNotBlank() }.map { eq to it }
                        }
                        if (activityItems.isEmpty()) {
                            EmptyReportBox("暂无活动描述记录，可在部件检测页“风险评价与活动描述”中补充。")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(activityItems, key = { it.second.id }) { (eq, part) ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "${eq.tagNumber} · ${part.componentGroup} / ${part.partName}",
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = part.activityDescription,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val targetSection = if (selectedTab == 0) SectionType.EQUIPMENT else SectionType.INFRASTRUCTURE
                        val rows = remember(dataItems, targetSection) {
                            dataItems.filter { it.first.resolvedSectionType == targetSection }
                                .flatMap { (eq, parts) ->
                                    parts.mapIndexed { idx, part ->
                                        ReportFlatRow(
                                            equipment = eq,
                                            isFirstForEquipment = idx == 0,
                                            part = part
                                        )
                                    }
                                }
                        }

                        if (rows.isEmpty()) {
                            EmptyReportBox(
                                if (targetSection == SectionType.EQUIPMENT) "暂无设备材质数据，请先登记设备并完成检测。"
                                else "暂无车间基建单元数据，可在首页登记“车间单元基建”模板。"
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                                    .horizontalScroll(hScroll)
                            ) {
                                TableHeaderRow(
                                    isEquipment = targetSection == SectionType.EQUIPMENT,
                                    eqFields = activeEqFields,
                                    elements = activeElements,
                                    detailFields = activeDetailFields
                                )
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(rows, key = { "${it.part.equipmentId}_${it.part.id}" }) { row ->
                                        TableDataRow(
                                            row = row,
                                            isEquipment = targetSection == SectionType.EQUIPMENT,
                                            eqFields = activeEqFields,
                                            elements = activeElements,
                                            detailFields = activeDetailFields,
                                            onPreviewPhoto = { label, file -> previewPhotoFile = label to file }
                                        )
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    previewPhotoFile?.let { (title, file) ->
        Dialog(onDismissRequest = { previewPhotoFile = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { previewPhotoFile = null }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                    AsyncImage(
                        model = file,
                        contentDescription = title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 440.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun TableHeaderRow(
    isEquipment: Boolean,
    eqFields: List<TemplateFieldDef>,
    elements: List<String>,
    detailFields: List<TemplateFieldDef>
) {
    val headerBg = MaterialTheme.colorScheme.primaryContainer
    val headerFg = MaterialTheme.colorScheme.onPrimaryContainer

    Row(
        modifier = Modifier
            .background(headerBg)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableCell("编号", 64.dp, headerFg, bold = true)
        if (isEquipment) {
            eqFields.forEach { f ->
                TableCell(f.label, widthForField(f.key), headerFg, bold = true)
            }
        } else {
            TableCell("车间位置", 116.dp, headerFg, bold = true)
            TableCell("类型", 112.dp, headerFg, bold = true)
            TableCell("所属系统", 76.dp, headerFg, bold = true)
        }
        TableCell("部件", 76.dp, headerFg, bold = true)
        TableCell("零件名称", 104.dp, headerFg, bold = true)
        TableCell("数量", 48.dp, headerFg, bold = true)
        for (sym in elements) {
            TableCell("$sym%", 58.dp, headerFg, bold = true)
        }
        detailFields.forEach { df ->
            TableCell(df.label, widthForField(df.key), headerFg, bold = true)
        }
    }
}

@Composable
private fun TableDataRow(
    row: ReportFlatRow,
    isEquipment: Boolean,
    eqFields: List<TemplateFieldDef>,
    elements: List<String>,
    detailFields: List<TemplateFieldDef>,
    onPreviewPhoto: (String, File) -> Unit
) {
    val eq = row.equipment
    val part = row.part
    val isVerified = part.resolvedStatus == PartStatus.VERIFIED
    val isNonMetal = part.resolvedStatus == PartStatus.NON_METAL
    val textColor = MaterialTheme.colorScheme.onSurface
    val rowBg = when {
        isVerified -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
        isNonMetal -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .background(rowBg)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableCell(part.codeNumber, 64.dp, textColor, mono = true)
        if (isEquipment) {
            eqFields.forEach { f ->
                val text = if (f.key == TemplateSchemaConfig.KEY_EQ_SYSTEM) {
                    part.systemName.ifBlank { eq.systemName }
                } else if (!row.isFirstForEquipment) {
                    ""
                } else {
                    when (f.key) {
                        TemplateSchemaConfig.KEY_EQ_TAG -> eq.tagNumber
                        TemplateSchemaConfig.KEY_EQ_NAME -> eq.equipmentName
                        TemplateSchemaConfig.KEY_EQ_SPEC -> eq.specModel
                        else -> eq.customFields[f.key].orEmpty()
                    }
                }
                TableCell(
                    text = text,
                    width = widthForField(f.key),
                    color = textColor,
                    bold = row.isFirstForEquipment && f.key == TemplateSchemaConfig.KEY_EQ_TAG
                )
            }
        } else {
            TableCell(if (row.isFirstForEquipment) eq.workshopLocation.ifBlank { eq.tagNumber } else "", 116.dp, textColor, bold = row.isFirstForEquipment)
            TableCell(if (row.isFirstForEquipment) eq.infraType.ifBlank { eq.equipmentName } else "", 112.dp, textColor)
            TableCell(part.systemName.ifBlank { eq.systemName }, 76.dp, textColor)
        }

        TableCell(part.componentGroup, 76.dp, textColor)
        TableCell(part.partName, 104.dp, textColor, bold = true)
        TableCell(part.quantity, 48.dp, textColor, mono = true)

        for (sym in elements) {
            val txt = when {
                isNonMetal -> "-"
                part.elements[sym] != null -> String.format(Locale.US, "%.3f", part.elements[sym]!!).trimEnd('0').trimEnd('.')
                sym in part.ndElements -> "ND"
                else -> ""
            }
            TableCell(
                text = txt,
                width = 58.dp,
                color = if (part.elements[sym] != null) MaterialTheme.colorScheme.primary else textColor,
                mono = true
            )
        }

        detailFields.forEach { df ->
            if (df.key == TemplateSchemaConfig.KEY_PART_PHOTO) {
                val photoFile = part.photoPath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                Box(
                    modifier = Modifier
                        .width(86.dp)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (photoFile != null) {
                        AsyncImage(
                            model = photoFile,
                            contentDescription = "留存照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPreviewPhoto("${eq.tagNumber} · ${part.partName}", photoFile) }
                        )
                    } else {
                        Text("-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                val value = when (df.key) {
                    TemplateSchemaConfig.KEY_PART_MATERIAL -> part.materialType
                    TemplateSchemaConfig.KEY_PART_CUZN -> part.cuZnImpurity.ifBlank { part.computeCuZnImpurityDisplay() }
                    TemplateSchemaConfig.KEY_PART_GRADE -> part.detectedGrade
                    TemplateSchemaConfig.KEY_PART_SEVERITY_S -> part.severityS
                    TemplateSchemaConfig.KEY_PART_MOTION_STATE -> part.motionState
                    TemplateSchemaConfig.KEY_PART_MOTION_EVAL -> part.motionEval
                    TemplateSchemaConfig.KEY_PART_LOCATION_POS -> part.locationPos
                    TemplateSchemaConfig.KEY_PART_FREQUENCY_O -> part.frequencyO
                    TemplateSchemaConfig.KEY_PART_DETECTION_METHOD -> part.detectionMethod
                    TemplateSchemaConfig.KEY_PART_DETECTION_D -> part.detectionD
                    TemplateSchemaConfig.KEY_PART_FAILURE_CONSEQUENCE -> part.failureConsequence
                    TemplateSchemaConfig.KEY_PART_RISK_LEVEL -> part.riskLevel
                    TemplateSchemaConfig.KEY_PART_CONTROL_MEASURE -> part.controlMeasure
                    else -> part.customFields[df.key].orEmpty()
                }
                TableCell(
                    text = value,
                    width = widthForField(df.key),
                    color = textColor,
                    mono = df.key == TemplateSchemaConfig.KEY_PART_CUZN
                )
            }
        }
    }
}

private fun widthForField(key: String): Dp = when (key) {
    TemplateSchemaConfig.KEY_EQ_TAG -> 116.dp
    TemplateSchemaConfig.KEY_EQ_NAME -> 112.dp
    TemplateSchemaConfig.KEY_EQ_SPEC -> 148.dp
    TemplateSchemaConfig.KEY_EQ_SYSTEM -> 76.dp
    TemplateSchemaConfig.KEY_PART_MATERIAL -> 72.dp
    TemplateSchemaConfig.KEY_PART_CUZN -> 78.dp
    TemplateSchemaConfig.KEY_PART_GRADE -> 88.dp
    TemplateSchemaConfig.KEY_PART_PHOTO -> 86.dp
    TemplateSchemaConfig.KEY_PART_CONTROL_MEASURE -> 140.dp
    else -> 92.dp
}

@Composable
private fun TableCell(
    text: String,
    width: Dp,
    color: Color,
    bold: Boolean = false,
    mono: Boolean = false
) {
    Text(
        text = text,
        style = if (mono) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 6.dp)
    )
}

@Composable
private fun EmptyReportBox(message: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TableChart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
