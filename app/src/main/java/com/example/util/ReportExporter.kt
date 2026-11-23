package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.model.ComponentInspection
import com.example.model.EquipmentRecord
import com.example.model.InspectionReportMeta
import com.example.model.PartStatus
import com.example.model.SectionType
import com.example.model.TemplateFieldDef
import com.example.model.TemplateSchemaConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val XLSX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

data class ExportedXlsxResult(
    val uri: Uri,
    val fileName: String,
    val displayLocation: String,
    val embeddedPhotoCount: Int = 0
)

private sealed interface XlsxCellValue {
    data class Text(val value: String, val style: Int) : XlsxCellValue
    data class Num(val value: Double, val style: Int) : XlsxCellValue
}

private data class SheetRowSpec(
    val rowNumber: Int,
    val cells: List<XlsxCellValue>,
    val heightPt: Int? = null
)

private data class EmbeddedMediaImage(
    val mediaEntryName: String,     // e.g. "xl/media/image1.jpg"
    val drawingRelTarget: String,   // e.g. "../media/image1.jpg"
    val relId: String,              // e.g. "rId1"
    val bytes: ByteArray,
    val widthEmu: Long,
    val heightEmu: Long,
    val colZeroBased: Int,
    val rowZeroBased: Int
)

private data class SheetBuildResult(
    val sheetXml: String,
    val drawingEntryPath: String?,
    val drawingRelPath: String?,
    val sheetRelPath: String?,
    val drawingXml: String?,
    val drawingRelsXml: String?,
    val sheetRelsXml: String?,
    val mediaImages: List<EmbeddedMediaImage>
)

object ReportExporter {

    fun defaultXlsxFileName(meta: InspectionReportMeta): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val safeProcess = meta.processName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "质检" }
        return "${safeProcess}_材质成分分析表_$stamp.xlsx"
    }

    /**
     * Generates a valid OOXML (.xlsx) workbook matching the customer template and current
     * TemplateSchemaConfig (with user-added/removed fields and embedded spectrometer photos).
     */
    fun buildTemplateXlsxBytes(
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig = TemplateSchemaConfig()
    ): ByteArray {
        val mediaCounter = intArrayOf(1)
        val sheet1 = buildEquipmentSheet(meta, items, templateConfig, mediaCounter)
        val sheet2 = buildInfraSheet(meta, items, templateConfig, mediaCounter)
        val sheet3Xml = buildActivitySheetXml(meta, items)

        val drawingOverrides = listOfNotNull(
            sheet1.drawingEntryPath?.let { "/$it" },
            sheet2.drawingEntryPath?.let { "/$it" }
        )
        val hasAnyImages = sheet1.mediaImages.isNotEmpty() || sheet2.mediaImages.isNotEmpty()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            writeZipEntry(zos, "[Content_Types].xml", contentTypesXml(hasAnyImages, drawingOverrides))
            writeZipEntry(zos, "_rels/.rels", rootRelsXml())
            writeZipEntry(zos, "xl/workbook.xml", workbookXml())
            writeZipEntry(zos, "xl/_rels/workbook.xml.rels", workbookRelsXml())
            writeZipEntry(zos, "xl/styles.xml", stylesXml())

            writeZipEntry(zos, "xl/worksheets/sheet1.xml", sheet1.sheetXml)
            writeZipEntry(zos, "xl/worksheets/sheet2.xml", sheet2.sheetXml)
            writeZipEntry(zos, "xl/worksheets/sheet3.xml", sheet3Xml)

            listOf(sheet1, sheet2).forEach { built ->
                if (built.drawingEntryPath != null && built.drawingXml != null &&
                    built.drawingRelPath != null && built.drawingRelsXml != null &&
                    built.sheetRelPath != null && built.sheetRelsXml != null
                ) {
                    writeZipEntry(zos, built.sheetRelPath, built.sheetRelsXml)
                    writeZipEntry(zos, built.drawingEntryPath, built.drawingXml)
                    writeZipEntry(zos, built.drawingRelPath, built.drawingRelsXml)
                    built.mediaImages.forEach { img ->
                        zos.putNextEntry(ZipEntry(img.mediaEntryName))
                        zos.write(img.bytes)
                        zos.closeEntry()
                    }
                }
            }
        }
        return baos.toByteArray()
    }

    fun exportToDownloadsFolder(
        context: Context,
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig = TemplateSchemaConfig()
    ): Result<ExportedXlsxResult> {
        return try {
            val bytes = buildTemplateXlsxBytes(meta, items, templateConfig)
            val fileName = defaultXlsxFileName(meta)
            val photoCount = countExportedPhotos(items, templateConfig)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME_TYPE)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/SpectraQC")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("无法在手机下载目录创建文件")
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: error("无法写入 XLSX 文件流")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Result.success(
                    ExportedXlsxResult(
                        uri = uri,
                        fileName = fileName,
                        displayLocation = "内部存储 / Download / SpectraQC / $fileName",
                        embeddedPhotoCount = photoCount
                    )
                )
            } else {
                val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(exportDir, fileName)
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                Result.success(
                    ExportedXlsxResult(
                        uri = uri,
                        fileName = fileName,
                        displayLocation = "已生成 $fileName（可通过“另存为”或打开查看）",
                        embeddedPhotoCount = photoCount
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun writeXlsxToUri(
        context: Context,
        uri: Uri,
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig = TemplateSchemaConfig()
    ): Result<Int> {
        return try {
            val bytes = buildTemplateXlsxBytes(meta, items, templateConfig)
            val photoCount = countExportedPhotos(items, templateConfig)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: error("无法打开目标文件输出流")
            Result.success(photoCount)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun countExportedPhotos(
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig
    ): Int {
        if (!templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_PHOTO)) return 0
        return items.sumOf { (_, parts) ->
            parts.count { it.photoPath.isNotBlank() && File(it.photoPath).exists() }
        }
    }

    fun createViewXlsxIntent(uri: Uri): Intent {
        return Intent.createChooser(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, XLSX_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "选择表格应用打开 XLSX"
        )
    }

    fun createShareXlsxIntent(uri: Uri, subject: String): Intent {
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = XLSX_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "分享质检 XLSX 报表"
        )
    }

    private fun buildEquipmentSheet(
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig,
        mediaCounter: IntArray
    ): SheetBuildResult {
        val activeEqFields = templateConfig.activeEquipmentFields()
        val activeElements = templateConfig.activeElementSymbols()
        val activeDetailFields = templateConfig.activePartDetailFields()
        val showPhotoCol = activeDetailFields.any { it.key == TemplateSchemaConfig.KEY_PART_PHOTO }

        // Columns: [编号] + [equipment fields] + [部件名称, 零件名称, 零件数量] + [elements] + [detail fields]
        val eqCount = activeEqFields.size
        val partBaseCount = 3
        val elemCount = activeElements.size
        val detailCount = activeDetailFields.size
        val totalCols = 1 + eqCount + partBaseCount + elemCount + detailCount
        val lastColLetter = colLetter(totalCols - 1)
        val photoColIdx = if (showPhotoCol) {
            1 + eqCount + partBaseCount + elemCount + activeDetailFields.indexOfFirst { it.key == TemplateSchemaConfig.KEY_PART_PHOTO }
        } else -1

        val rows = mutableListOf<SheetRowSpec>()
        val merges = mutableListOf<String>()

        rows.add(
            SheetRowSpec(
                1,
                listOf(XlsxCellValue.Text("工序：${meta.processName}    |    ${meta.docTitle}", 1))
            )
        )
        merges.add("A1:${lastColLetter}1")

        rows.add(
            SheetRowSpec(
                2,
                listOf(
                    XlsxCellValue.Text(
                        "文件编号：${meta.docCode}   版本：${meta.version}   保密等级：${meta.securityLevel}   批准人：${meta.approver}   生效日期：${meta.effectiveDate}",
                        4
                    )
                )
            )
        )
        merges.add("A2:${lastColLetter}2")

        rows.add(SheetRowSpec(4, listOf(XlsxCellValue.Text("三、设备&部件材质成分分析表", 1))))
        merges.add("A4:${lastColLetter}4")

        // Group Headers Row 5
        val groupRow = MutableList<XlsxCellValue>(totalCols) { XlsxCellValue.Text("", 2) }
        groupRow[0] = XlsxCellValue.Text("序号", 2)
        val eqStart = 1
        val eqEnd = eqStart + eqCount - 1
        val partStart = eqEnd + 1
        val partEnd = partStart + partBaseCount - 1
        val elemStart = partEnd + 1
        val elemEnd = elemStart + elemCount - 1
        val detailStart = elemEnd + 1
        val detailEnd = detailStart + detailCount - 1

        if (eqCount > 0) {
            groupRow[eqStart] = XlsxCellValue.Text("设备信息", 2)
            if (eqEnd > eqStart) merges.add("${colLetter(eqStart)}5:${colLetter(eqEnd)}5")
        }
        groupRow[partStart] = XlsxCellValue.Text("部件信息", 2)
        if (partEnd > partStart) merges.add("${colLetter(partStart)}5:${colLetter(partEnd)}5")
        if (elemCount > 0) {
            groupRow[elemStart] = XlsxCellValue.Text("组成成分 (%)", 2)
            if (elemEnd > elemStart) merges.add("${colLetter(elemStart)}5:${colLetter(elemEnd)}5")
        }
        if (detailCount > 0) {
            groupRow[detailStart] = XlsxCellValue.Text("材质、风险评价与留存照片", 2)
            if (detailEnd > detailStart) merges.add("${colLetter(detailStart)}5:${colLetter(detailEnd)}5")
        }
        rows.add(SheetRowSpec(5, groupRow))

        // Exact Column Headers Row 6
        val colHeaders = buildList {
            add("编号")
            activeEqFields.forEach { add(it.label) }
            addAll(listOf("部件名称", "零件名称", "零件数量"))
            addAll(activeElements)
            activeDetailFields.forEach { add(it.label) }
        }
        rows.add(SheetRowSpec(6, colHeaders.map { XlsxCellValue.Text(it, 3) }))

        val embeddedImages = mutableListOf<EmbeddedMediaImage>()
        var rowNum = 7
        val eqItems = items.filter { it.first.resolvedSectionType == SectionType.EQUIPMENT }

        eqItems.forEach { (eq, parts) ->
            parts.forEachIndexed { idx, part ->
                val isFirst = idx == 0
                val isNonMetal = part.resolvedStatus == PartStatus.NON_METAL || part.materialType == "非金属"
                val isVerified = part.resolvedStatus == PartStatus.VERIFIED
                val cellRow = mutableListOf<XlsxCellValue>()

                cellRow.add(XlsxCellValue.Text(part.codeNumber, 4))
                activeEqFields.forEach { eqField ->
                    val valStr = if (eqField.key == TemplateSchemaConfig.KEY_EQ_SYSTEM) {
                        part.systemName.ifBlank { eq.systemName }
                    } else if (!isFirst) {
                        ""
                    } else {
                        readEquipmentFieldValue(eq, eqField)
                    }
                    cellRow.add(XlsxCellValue.Text(valStr, 4))
                }
                cellRow.add(XlsxCellValue.Text(part.componentGroup, 4))
                cellRow.add(XlsxCellValue.Text(part.partName, 4))
                cellRow.add(XlsxCellValue.Text(part.quantity, 4))

                val elemStyle = if (isVerified) 5 else 4
                for (sym in activeElements) {
                    if (isNonMetal) {
                        cellRow.add(XlsxCellValue.Text("-", 4))
                    } else {
                        val v = part.elements[sym]
                        if (v != null) cellRow.add(XlsxCellValue.Num(v, elemStyle))
                        else cellRow.add(XlsxCellValue.Text("", elemStyle))
                    }
                }

                var rowHasImage = false
                activeDetailFields.forEach { df ->
                    if (df.key == TemplateSchemaConfig.KEY_PART_PHOTO) {
                        val imgAdded = tryBuildEmbeddedImage(
                            photoPath = part.photoPath,
                            mediaCounter = mediaCounter,
                            relIndex = embeddedImages.size + 1,
                            colZeroBased = photoColIdx,
                            rowZeroBased = rowNum - 1
                        )
                        if (imgAdded != null) {
                            embeddedImages += imgAdded
                            rowHasImage = true
                            cellRow.add(XlsxCellValue.Text("", 4))
                        } else {
                            cellRow.add(XlsxCellValue.Text("未拍照", 4))
                        }
                    } else {
                        val txt = readPartDetailFieldValue(part, df)
                        val style = if (df.key == TemplateSchemaConfig.KEY_PART_MATERIAL && isVerified) 6 else 4
                        cellRow.add(XlsxCellValue.Text(txt, style))
                    }
                }

                rows.add(SheetRowSpec(rowNum, cellRow, heightPt = if (rowHasImage) 72 else null))
                rowNum++
            }
        }

        val widths = buildList {
            add(10)
            activeEqFields.forEach { f ->
                add(
                    when (f.key) {
                        TemplateSchemaConfig.KEY_EQ_TAG -> 16
                        TemplateSchemaConfig.KEY_EQ_NAME -> 18
                        TemplateSchemaConfig.KEY_EQ_SPEC -> 24
                        TemplateSchemaConfig.KEY_EQ_SYSTEM -> 11
                        else -> 15
                    }
                )
            }
            addAll(listOf(12, 16, 8))
            repeat(elemCount) { add(8) }
            activeDetailFields.forEach { df ->
                add(
                    when (df.key) {
                        TemplateSchemaConfig.KEY_PART_PHOTO -> 18
                        TemplateSchemaConfig.KEY_PART_CONTROL_MEASURE -> 22
                        TemplateSchemaConfig.KEY_PART_FAILURE_CONSEQUENCE -> 18
                        else -> 13
                    }
                )
            }
        }

        val drawingRelId = if (embeddedImages.isNotEmpty()) "rId1" else null
        val xml = buildWorksheetXml(widths, rows, merges, drawingRelId)
        return packageSheetWithDrawing(
            sheetXml = xml,
            sheetIndex = 1,
            drawingIndex = 1,
            embeddedImages = embeddedImages
        )
    }

    private fun buildInfraSheet(
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>,
        templateConfig: TemplateSchemaConfig,
        mediaCounter: IntArray
    ): SheetBuildResult {
        val activeElements = templateConfig.activeElementSymbols()
        val activeDetailFields = templateConfig.activePartDetailFields()
        val showPhotoCol = activeDetailFields.any { it.key == TemplateSchemaConfig.KEY_PART_PHOTO }
        val systemLabel = templateConfig.labelFor(TemplateSchemaConfig.KEY_EQ_SYSTEM, "所属系统")

        val baseCols = 7 // 编号, 车间位置, 类型, 所属系统, 部件名称, 零件名称, 数量
        val totalCols = baseCols + activeElements.size + activeDetailFields.size
        val lastColLetter = colLetter(totalCols - 1)
        val photoColIdx = if (showPhotoCol) {
            baseCols + activeElements.size + activeDetailFields.indexOfFirst { it.key == TemplateSchemaConfig.KEY_PART_PHOTO }
        } else -1

        val rows = mutableListOf<SheetRowSpec>()
        val merges = mutableListOf<String>()

        rows.add(SheetRowSpec(1, listOf(XlsxCellValue.Text("工序：${meta.processName}  |  四、设备&部件所处车间单元基建成分分析表", 1))))
        merges.add("A1:${lastColLetter}1")

        val groupRow = MutableList<XlsxCellValue>(totalCols) { XlsxCellValue.Text("", 2) }
        groupRow[0] = XlsxCellValue.Text("编号", 2)
        groupRow[1] = XlsxCellValue.Text("车间基建清单", 2)
        groupRow[3] = XlsxCellValue.Text("部件信息", 2)
        if (activeElements.isNotEmpty()) {
            groupRow[7] = XlsxCellValue.Text("组成成分 (%)", 2)
            if (activeElements.size > 1) merges.add("${colLetter(7)}3:${colLetter(7 + activeElements.size - 1)}3")
        }
        val detailStart = 7 + activeElements.size
        if (activeDetailFields.isNotEmpty()) {
            groupRow[detailStart] = XlsxCellValue.Text("风险评价与照片", 2)
            if (activeDetailFields.size > 1) merges.add("${colLetter(detailStart)}3:${colLetter(totalCols - 1)}3")
        }
        rows.add(SheetRowSpec(3, groupRow))
        merges.add("B3:C3")
        merges.add("D3:G3")

        val colHeaders = buildList {
            addAll(listOf("编号", "车间位置", "类型", systemLabel, "部件名称", "零件名称", "数量"))
            addAll(activeElements)
            activeDetailFields.forEach { add(it.label) }
        }
        rows.add(SheetRowSpec(4, colHeaders.map { XlsxCellValue.Text(it, 3) }))

        val embeddedImages = mutableListOf<EmbeddedMediaImage>()
        var rowNum = 5
        val infraItems = items.filter { it.first.resolvedSectionType == SectionType.INFRASTRUCTURE }
        infraItems.forEach { (eq, parts) ->
            parts.forEachIndexed { idx, part ->
                val isFirst = idx == 0
                val isNonMetal = part.resolvedStatus == PartStatus.NON_METAL || part.materialType == "非金属"
                val isVerified = part.resolvedStatus == PartStatus.VERIFIED
                val cellRow = mutableListOf<XlsxCellValue>()

                cellRow.add(XlsxCellValue.Text(part.codeNumber, 4))
                cellRow.add(XlsxCellValue.Text(if (isFirst) eq.workshopLocation.ifBlank { eq.tagNumber } else "", 4))
                cellRow.add(XlsxCellValue.Text(if (isFirst) eq.infraType.ifBlank { eq.equipmentName } else "", 4))
                cellRow.add(XlsxCellValue.Text(part.systemName.ifBlank { eq.systemName }, 4))
                cellRow.add(XlsxCellValue.Text(part.componentGroup, 4))
                cellRow.add(XlsxCellValue.Text(part.partName, 4))
                cellRow.add(XlsxCellValue.Text(part.quantity, 4))

                val elemStyle = if (isVerified) 5 else 4
                for (sym in activeElements) {
                    if (isNonMetal) {
                        cellRow.add(XlsxCellValue.Text("-", 4))
                    } else {
                        val v = part.elements[sym]
                        if (v != null) cellRow.add(XlsxCellValue.Num(v, elemStyle))
                        else cellRow.add(XlsxCellValue.Text("", elemStyle))
                    }
                }

                var rowHasImage = false
                activeDetailFields.forEach { df ->
                    if (df.key == TemplateSchemaConfig.KEY_PART_PHOTO) {
                        val imgAdded = tryBuildEmbeddedImage(
                            photoPath = part.photoPath,
                            mediaCounter = mediaCounter,
                            relIndex = embeddedImages.size + 1,
                            colZeroBased = photoColIdx,
                            rowZeroBased = rowNum - 1
                        )
                        if (imgAdded != null) {
                            embeddedImages += imgAdded
                            rowHasImage = true
                            cellRow.add(XlsxCellValue.Text("", 4))
                        } else {
                            cellRow.add(XlsxCellValue.Text("未拍照", 4))
                        }
                    } else {
                        val txt = readPartDetailFieldValue(part, df)
                        val style = if (df.key == TemplateSchemaConfig.KEY_PART_MATERIAL && isVerified) 6 else 4
                        cellRow.add(XlsxCellValue.Text(txt, style))
                    }
                }

                rows.add(SheetRowSpec(rowNum, cellRow, heightPt = if (rowHasImage) 72 else null))
                rowNum++
            }
        }

        val widths = buildList {
            addAll(listOf(10, 18, 16, 10, 12, 16, 8))
            repeat(activeElements.size) { add(8) }
            activeDetailFields.forEach { df ->
                add(
                    when (df.key) {
                        TemplateSchemaConfig.KEY_PART_PHOTO -> 18
                        TemplateSchemaConfig.KEY_PART_CONTROL_MEASURE -> 22
                        else -> 13
                    }
                )
            }
        }

        val drawingRelId = if (embeddedImages.isNotEmpty()) "rId1" else null
        val xml = buildWorksheetXml(widths, rows, merges, drawingRelId)
        return packageSheetWithDrawing(
            sheetXml = xml,
            sheetIndex = 2,
            drawingIndex = 2,
            embeddedImages = embeddedImages
        )
    }

    private fun buildActivitySheetXml(
        meta: InspectionReportMeta,
        items: List<Pair<EquipmentRecord, List<ComponentInspection>>>
    ): String {
        val rows = mutableListOf<SheetRowSpec>()
        val merges = mutableListOf<String>()

        rows.add(SheetRowSpec(1, listOf(XlsxCellValue.Text("工序：${meta.processName}  |  二、活动描述 / 工作原理", 1))))
        merges.add("A1:F1")

        rows.add(
            SheetRowSpec(
                3,
                listOf("设备位号", "设备名称", "所属系统", "部件名称", "零件名称", "活动描述 & 功能")
                    .map { XlsxCellValue.Text(it, 3) }
            )
        )

        var rowNum = 4
        items.forEach { (eq, parts) ->
            parts.filter { it.activityDescription.isNotBlank() }.forEach { part ->
                rows.add(
                    SheetRowSpec(
                        rowNum,
                        listOf(
                            XlsxCellValue.Text(eq.tagNumber, 4),
                            XlsxCellValue.Text(eq.equipmentName, 4),
                            XlsxCellValue.Text(part.systemName.ifBlank { eq.systemName }, 4),
                            XlsxCellValue.Text(part.componentGroup, 4),
                            XlsxCellValue.Text(part.partName, 4),
                            XlsxCellValue.Text(part.activityDescription, 4)
                        )
                    )
                )
                rowNum++
            }
        }

        return buildWorksheetXml(listOf(16, 18, 12, 14, 16, 48), rows, merges, drawingRelId = null)
    }

    private fun readEquipmentFieldValue(eq: EquipmentRecord, field: TemplateFieldDef): String =
        when (field.key) {
            TemplateSchemaConfig.KEY_EQ_TAG -> eq.tagNumber
            TemplateSchemaConfig.KEY_EQ_NAME -> eq.equipmentName
            TemplateSchemaConfig.KEY_EQ_SPEC -> eq.specModel
            TemplateSchemaConfig.KEY_EQ_SYSTEM -> eq.systemName
            else -> eq.customFields[field.key].orEmpty()
        }

    private fun readPartDetailFieldValue(part: ComponentInspection, field: TemplateFieldDef): String =
        when (field.key) {
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
            else -> part.customFields[field.key].orEmpty()
        }

    private fun tryBuildEmbeddedImage(
        photoPath: String,
        mediaCounter: IntArray,
        relIndex: Int,
        colZeroBased: Int,
        rowZeroBased: Int
    ): EmbeddedMediaImage? {
        if (photoPath.isBlank() || colZeroBased < 0) return null
        val file = File(photoPath)
        if (!file.exists() || file.length() <= 0L) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null

        var rawW = 400
        var rawH = 300
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                rawW = bounds.outWidth
                rawH = bounds.outHeight
            }
        }

        // Fit inside cell (~116px wide x 86px tall), preserving aspect ratio (1 px ≈ 9525 EMU)
        val maxW = 116.0
        val maxH = 86.0
        val scale = minOf(maxW / rawW.toDouble(), maxH / rawH.toDouble())
        val targetWPx = (rawW * scale).coerceAtLeast(24.0)
        val targetHPx = (rawH * scale).coerceAtLeast(24.0)

        val imgIdx = mediaCounter[0]++
        return EmbeddedMediaImage(
            mediaEntryName = "xl/media/image$imgIdx.jpg",
            drawingRelTarget = "../media/image$imgIdx.jpg",
            relId = "rId$relIndex",
            bytes = bytes,
            widthEmu = (targetWPx * 9525.0).toLong(),
            heightEmu = (targetHPx * 9525.0).toLong(),
            colZeroBased = colZeroBased,
            rowZeroBased = rowZeroBased
        )
    }

    private fun packageSheetWithDrawing(
        sheetXml: String,
        sheetIndex: Int,
        drawingIndex: Int,
        embeddedImages: List<EmbeddedMediaImage>
    ): SheetBuildResult {
        if (embeddedImages.isEmpty()) {
            return SheetBuildResult(sheetXml, null, null, null, null, null, null, emptyList())
        }
        val drawingEntry = "xl/drawings/drawing$drawingIndex.xml"
        val drawingRel = "xl/drawings/_rels/drawing$drawingIndex.xml.rels"
        val sheetRel = "xl/worksheets/_rels/sheet$sheetIndex.xml.rels"

        val sheetRelsXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n")
            append("  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing$drawingIndex.xml\"/>\n")
            append("</Relationships>")
        }

        val drawingRelsXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n")
            embeddedImages.forEach { img ->
                append("  <Relationship Id=\"${img.relId}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"${img.drawingRelTarget}\"/>\n")
            }
            append("</Relationships>")
        }

        val drawingXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n")
            embeddedImages.forEachIndexed { idx, img ->
                val picId = idx + 1
                val padEmu = 38100L // ~4px padding inside cell
                append("  <xdr:twoCellAnchor editAs=\"oneCell\">\n")
                append("    <xdr:from>\n")
                append("      <xdr:col>${img.colZeroBased}</xdr:col>\n")
                append("      <xdr:colOff>$padEmu</xdr:colOff>\n")
                append("      <xdr:row>${img.rowZeroBased}</xdr:row>\n")
                append("      <xdr:rowOff>$padEmu</xdr:rowOff>\n")
                append("    </xdr:from>\n")
                append("    <xdr:to>\n")
                append("      <xdr:col>${img.colZeroBased}</xdr:col>\n")
                append("      <xdr:colOff>${padEmu + img.widthEmu}</xdr:colOff>\n")
                append("      <xdr:row>${img.rowZeroBased}</xdr:row>\n")
                append("      <xdr:rowOff>${padEmu + img.heightEmu}</xdr:rowOff>\n")
                append("    </xdr:to>\n")
                append("    <xdr:pic>\n")
                append("      <xdr:nvPicPr>\n")
                append("        <xdr:cNvPr id=\"$picId\" name=\"SpectraPhoto$picId\"/>\n")
                append("        <xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr>\n")
                append("      </xdr:nvPicPr>\n")
                append("      <xdr:blipFill>\n")
                append("        <a:blip r:embed=\"${img.relId}\"/>\n")
                append("        <a:stretch><a:fillRect/></a:stretch>\n")
                append("      </xdr:blipFill>\n")
                append("      <xdr:spPr>\n")
                append("        <a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${img.widthEmu}\" cy=\"${img.heightEmu}\"/></a:xfrm>\n")
                append("        <a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>\n")
                append("      </xdr:spPr>\n")
                append("    </xdr:pic>\n")
                append("    <xdr:clientData/>\n")
                append("  </xdr:twoCellAnchor>\n")
            }
            append("</xdr:wsDr>")
        }

        return SheetBuildResult(
            sheetXml = sheetXml,
            drawingEntryPath = drawingEntry,
            drawingRelPath = drawingRel,
            sheetRelPath = sheetRel,
            drawingXml = drawingXml,
            drawingRelsXml = drawingRelsXml,
            sheetRelsXml = sheetRelsXml,
            mediaImages = embeddedImages
        )
    }

    private fun buildWorksheetXml(
        colWidths: List<Int>,
        rows: List<SheetRowSpec>,
        merges: List<String>,
        drawingRelId: String?
    ): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n")

        sb.append("<cols>\n")
        colWidths.forEachIndexed { idx, w ->
            val col = idx + 1
            sb.append("<col min=\"$col\" max=\"$col\" width=\"$w\" customWidth=\"1\"/>\n")
        }
        sb.append("</cols>\n")

        sb.append("<sheetData>\n")
        rows.forEach { rowSpec ->
            val heightAttr = if (rowSpec.heightPt != null) " ht=\"${rowSpec.heightPt}\" customHeight=\"1\"" else ""
            sb.append("<row r=\"${rowSpec.rowNumber}\"$heightAttr>\n")
            rowSpec.cells.forEachIndexed { colIdx, cell ->
                val ref = "${colLetter(colIdx)}${rowSpec.rowNumber}"
                when (cell) {
                    is XlsxCellValue.Text -> {
                        val escaped = escapeXml(cell.value)
                        sb.append("<c r=\"$ref\" s=\"${cell.style}\" t=\"inlineStr\"><is><t>$escaped</t></is></c>\n")
                    }
                    is XlsxCellValue.Num -> {
                        val formatted = String.format(Locale.US, "%.4f", cell.value).trimEnd('0').trimEnd('.')
                        sb.append("<c r=\"$ref\" s=\"${cell.style}\" t=\"n\"><v>$formatted</v></c>\n")
                    }
                }
            }
            sb.append("</row>\n")
        }
        sb.append("</sheetData>\n")

        if (merges.isNotEmpty()) {
            sb.append("<mergeCells count=\"${merges.size}\">\n")
            merges.forEach { range ->
                sb.append("<mergeCell ref=\"$range\"/>\n")
            }
            sb.append("</mergeCells>\n")
        }

        if (drawingRelId != null) {
            sb.append("<drawing r:id=\"$drawingRelId\"/>\n")
        }

        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun colLetter(zeroBasedIndex: Int): String {
        var idx = zeroBasedIndex
        val sb = StringBuilder()
        while (idx >= 0) {
            sb.insert(0, ('A'.code + (idx % 26)).toChar())
            idx = (idx / 26) - 1
        }
        return sb.toString()
    }

    private fun escapeXml(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (ch >= ' ' || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun writeZipEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private fun contentTypesXml(hasImages: Boolean, drawingOverrides: List<String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n")
        append("  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n")
        append("  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n")
        if (hasImages) {
            append("  <Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>\n")
            append("  <Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>\n")
        }
        append("  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n")
        append("  <Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n")
        append("  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n")
        append("  <Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n")
        append("  <Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n")
        drawingOverrides.forEach { path ->
            append("  <Override PartName=\"$path\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>\n")
        }
        append("</Types>")
    }

    private fun rootRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookRelsXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
          <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
          <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
        </Relationships>
    """.trimIndent()

    private fun workbookXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets>
            <sheet name="设备部件材质成分分析表" sheetId="1" r:id="rId1"/>
            <sheet name="车间基建成分分析表" sheetId="2" r:id="rId2"/>
            <sheet name="活动描述与工作原理" sheetId="3" r:id="rId3"/>
          </sheets>
        </workbook>
    """.trimIndent()

    private fun stylesXml(): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <fonts count="4">
            <font><sz val="10"/><name val="Microsoft YaHei"/></font>
            <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Microsoft YaHei"/></font>
            <font><b/><sz val="10"/><color rgb="FF0F172A"/><name val="Microsoft YaHei"/></font>
            <font><b/><sz val="13"/><color rgb="FF0F1E36"/><name val="Microsoft YaHei"/></font>
          </fonts>
          <fills count="6">
            <fill><patternFill patternType="none"/></fill>
            <fill><patternFill patternType="gray125"/></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FF0F1E36"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFDCEEFB"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFFFF59D"/></patternFill></fill>
            <fill><patternFill patternType="solid"><fgColor rgb="FFE6F4EA"/></patternFill></fill>
          </fills>
          <borders count="2">
            <border><left/><right/><top/><bottom/><diagonal/></border>
            <border>
              <left style="thin"><color rgb="FFCBD5E1"/></left>
              <right style="thin"><color rgb="FFCBD5E1"/></right>
              <top style="thin"><color rgb="FFCBD5E1"/></top>
              <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
              <diagonal/>
            </border>
          </borders>
          <cellStyleXfs count="1">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
          </cellStyleXfs>
          <cellXfs count="7">
            <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
            <xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf>
            <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
            <xf numFmtId="0" fontId="2" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
            <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center"/></xf>
            <xf numFmtId="0" fontId="0" fillId="4" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf>
            <xf numFmtId="0" fontId="2" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>
          </cellXfs>
        </styleSheet>
    """.trimIndent()
}
