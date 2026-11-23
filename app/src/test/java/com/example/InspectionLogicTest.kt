package com.example

import com.example.data.OcrSpatialLine
import com.example.data.SpectrometerTextParser
import com.example.model.ComponentInspection
import com.example.model.ELEMENT_SYMBOLS
import com.example.model.EquipmentRecord
import com.example.model.EquipmentTemplateType
import com.example.model.FieldCategory
import com.example.model.InspectionReportMeta
import com.example.model.PartStatus
import com.example.model.SectionType
import com.example.model.TemplateFieldDef
import com.example.model.TemplatePresets
import com.example.model.TemplateSchemaConfig
import com.example.util.ReportExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

class InspectionLogicTest {

    @Test
    fun ballValveTemplateHas23PartsMatchingAttachment() {
        val parts = TemplatePresets.buildPartsForTemplate(EquipmentTemplateType.BALL_VALVE_O, 1L)
        assertEquals(23, parts.size)
        assertEquals("1.1.1", parts.first().codeSuffix)
        assertEquals("阀腔", parts.first().partName)
        assertEquals("阀杆", parts[4].partName)
        assertEquals(5L, parts[4].calloutNumber)
        assertEquals("铭牌", parts.last().partName)
    }

    @Test
    fun cuZnImpurityComputesCorrectSum() {
        val part = ComponentInspection(
            status = PartStatus.VERIFIED.name,
            materialType = "金属",
            elements = mapOf("Fe" to 70.1, "Cr" to 18.2, "Cu" to 0.15, "Zn" to 0.05)
        )
        assertEquals("0.20%", part.computeCuZnImpurityDisplay())

        val nonMetal = ComponentInspection(
            status = PartStatus.NON_METAL.name,
            materialType = "非金属",
            elements = mapOf("Cu" to 1.0)
        )
        assertEquals("0%", nonMetal.computeCuZnImpurityDisplay())
    }

    @Test
    fun offlineSpectrometerParserExtractsMultiColumnElementsPpmAndGrade() {
        val spatialLines = listOf(
            OcrSpatialLine("Alloy: SS 304", left = 10, top = 10, right = 140, bottom = 36),
            OcrSpatialLine("Fe", left = 10, top = 50, right = 45, bottom = 76),
            OcrSpatialLine("70.42%", left = 120, top = 51, right = 200, bottom = 77),
            OcrSpatialLine("Cr", left = 240, top = 50, right = 275, bottom = 76),
            OcrSpatialLine("18.15", left = 320, top = 52, right = 390, bottom = 78),
            OcrSpatialLine("Ni 8.92%   Cu 180ppm   Zn <LOD", left = 10, top = 95, right = 380, bottom = 121)
        )
        val parsed = SpectrometerTextParser.parseFromSpatialLines(spatialLines, ELEMENT_SYMBOLS)
        assertEquals("金属", parsed.materialType)
        assertEquals("SS304", parsed.detectedGrade)
        assertEquals(70.42, parsed.elements["Fe"]!!, 0.001)
        assertEquals(18.15, parsed.elements["Cr"]!!, 0.001)
        assertEquals(8.92, parsed.elements["Ni"]!!, 0.001)
        assertEquals(0.018, parsed.elements["Cu"]!!, 0.0001)
        assertFalse(parsed.elements.containsKey("Zn"))
    }

    @Test
    fun xlsxExportRespectsMaintainedFieldsRemovesCaliberAndEmbedsPhotos() {
        val tempPhoto = File.createTempFile("spectra_test_", ".jpg")
        try {
            // Minimal valid JPEG SOI/EOI bytes for attachment packaging verification
            tempPhoto.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

            val baseFields = TemplateSchemaConfig.defaultFields().map {
                if (it.key == "elem_Zr") it.copy(enabled = false) else it
            } + listOf(
                TemplateFieldDef(
                    key = "custom_pressure",
                    label = "设计压力",
                    category = FieldCategory.EQUIPMENT_INFO,
                    enabled = true,
                    isBuiltIn = false,
                    isCoreRequired = false,
                    enableWaterfallCandidates = true,
                    sortOrder = 100
                ),
                TemplateFieldDef(
                    key = "elem_C",
                    label = "C",
                    category = FieldCategory.ELEMENT,
                    enabled = true,
                    isBuiltIn = false,
                    isCoreRequired = false,
                    enableWaterfallCandidates = false,
                    sortOrder = 101
                )
            )
            val customConfig = TemplateSchemaConfig(baseFields)

            val meta = InspectionReportMeta(processName = "炼铁工序", docTitle = "设备材质表")
            val eq = EquipmentRecord(
                id = "eq_1",
                sequenceNo = 1L,
                tagNumber = "11XV-11272A",
                equipmentName = "切断O型球阀",
                specModel = "MODEL500S10",
                systemName = "冶铁",
                sectionType = SectionType.EQUIPMENT.name,
                customFields = mapOf("custom_pressure" to "PN16")
            )
            val part = ComponentInspection(
                id = "p_5",
                equipmentId = "eq_1",
                codeNumber = "1.1.5",
                componentGroup = "阀体",
                partName = "阀杆",
                quantity = "1",
                status = PartStatus.VERIFIED.name,
                materialType = "金属",
                detectedGrade = "SS304",
                activityDescription = "阀门启闭传动轴",
                photoPath = tempPhoto.absolutePath,
                elements = mapOf("Fe" to 70.4, "Cr" to 18.2, "Ni" to 8.9, "C" to 0.06)
            )

            val xlsxBytes = ReportExporter.buildTemplateXlsxBytes(meta, listOf(eq to listOf(part)), customConfig)
            assertTrue(xlsxBytes.isNotEmpty())

            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entries[entry.name] = zis.readBytes()
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val expectedPaths = listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/styles.xml",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/_rels/sheet1.xml.rels",
                "xl/drawings/drawing1.xml",
                "xl/drawings/_rels/drawing1.xml.rels",
                "xl/media/image1.jpg"
            )
            expectedPaths.forEach { path ->
                assertTrue("Missing OOXML entry $path", entries.containsKey(path))
            }

            val contentTypes = entries["[Content_Types].xml"]!!.toString(Charsets.UTF_8)
            assertTrue(contentTypes.contains("image/jpeg"))
            assertTrue(contentTypes.contains("/xl/drawings/drawing1.xml"))

            val sheet1 = entries["xl/worksheets/sheet1.xml"]!!.toString(Charsets.UTF_8)
            assertFalse("Standalone caliber column should be removed", sheet1.contains("口径/压力"))
            assertTrue(sheet1.contains("11XV-11272A"))
            assertTrue(sheet1.contains("设计压力"))
            assertTrue(sheet1.contains("PN16"))
            assertTrue(sheet1.contains("<t>C</t>"))
            assertFalse("Disabled Zr element should be omitted", sheet1.contains("<t>Zr</t>"))
            assertTrue(sheet1.contains("<v>0.06</v>"))
            assertTrue(sheet1.contains("ht=\"72\" customHeight=\"1\""))
            assertTrue(sheet1.contains("<drawing r:id=\"rId1\"/>"))

            val drawing1 = entries["xl/drawings/drawing1.xml"]!!.toString(Charsets.UTF_8)
            assertTrue(drawing1.contains("<a:blip r:embed=\"rId1\"/>"))
        } finally {
            tempPhoto.delete()
        }
    }
}
