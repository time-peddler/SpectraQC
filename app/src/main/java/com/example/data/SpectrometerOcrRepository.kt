package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.model.ELEMENT_SYMBOLS
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

data class SpectrometerOcrResult(
    val materialType: String,
    val detectedGrade: String,
    val elements: Map<String, Double>,
    val rawOcrSummary: String
)

data class OcrSpatialLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Pure Kotlin parser for offline XRF/OES spectrometer OCR text.
 * Supports spatial row reconstruction across multi-column spectrometer screens,
 * % and ppm unit conversion, <LOD/ND handling, alloy grade matching, and non-metal cues.
 */
object SpectrometerTextParser {

    private val gradePatterns = listOf(
        Regex("""\b(SS\s*304L?|304L?|SS\s*316L?|316L?|316Ti|321|310S|309S|2205|2507|17-4PH)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(WCB|WC6|WC9|LCC|CF8M?|CF3M?|A105|A182|F304|F316|F22)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(Q235[A-D]?|Q345[A-E]?|Q355[A-E]?|20#|45#|35CrMo|42CrMo|16Mn|HT200|QT450|QT500)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(H62|H59|HPb59-1|T2|CuZn\d+|ZCuSn\d+|3003|5052|6061|7075)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(PTFE|PFA|EPDM|FKM|NBR|PPL|PEEK|POM|NYLON)\b""", RegexOption.IGNORE_CASE)
    )

    private val nonMetalKeywords = listOf(
        "PTFE", "聚四氟", "四氟", "非金属", "橡胶", "塑料", "EPDM", "FKM", "NBR", "PPL", "无法激发", "NON-METAL"
    )

    fun reconstructSpatialRows(lines: List<OcrSpatialLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<OcrSpatialLine> { it.centerY }.thenBy { it.left })
        val rows = mutableListOf<MutableList<OcrSpatialLine>>()

        for (line in sorted) {
            val lastRow = rows.lastOrNull()
            if (lastRow == null) {
                rows.add(mutableListOf(line))
            } else {
                val avgCenterY = lastRow.map { it.centerY }.average()
                val minHeight = lastRow.minOf { it.height }.coerceAtMost(line.height)
                val tolerance = max(14.0, minHeight * 0.65)
                if (abs(line.centerY - avgCenterY) <= tolerance) {
                    lastRow.add(line)
                } else {
                    rows.add(mutableListOf(line))
                }
            }
        }

        return rows.map { row ->
            row.sortedBy { it.left }.joinToString("  ") { it.text.trim() }
        }.filter { it.isNotBlank() }
    }

    fun parseFromSpatialLines(
        lines: List<OcrSpatialLine>,
        targetElements: List<String> = ELEMENT_SYMBOLS
    ): SpectrometerOcrResult {
        val reconstructed = reconstructSpatialRows(lines)
        val combined = reconstructed.joinToString("\n")
        return parsePlainText(combined, targetElements)
    }

    fun parsePlainText(
        rawText: String,
        targetElements: List<String> = ELEMENT_SYMBOLS
    ): SpectrometerOcrResult {
        val cleanText = rawText
            .replace('％', '%')
            .replace('：', ':')
            .replace('＝', '=')
            .trim()

        if (cleanText.isBlank()) {
            return SpectrometerOcrResult(
                materialType = "金属",
                detectedGrade = "",
                elements = emptyMap(),
                rawOcrSummary = "未识别到清晰文字，请保持画面正对光谱仪屏幕或手动填写。"
            )
        }

        val canonicalByUpper = targetElements.associateBy { it.uppercase(Locale.US) }
        val symbolAlternation = canonicalByUpper.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }

        val elements = linkedMapOf<String, Double>()

        if (symbolAlternation.isNotBlank()) {
            // Pattern A: same-line symbol + value, e.g. "Fe 70.42%", "Cr: 18.2", "Cu 180ppm", "Zn <LOD"
            val inlineRegex = Regex(
                """(?<![A-Za-z])($symbolAlternation)\s*[:=]?\s*(?:(<LOD|ND|N\.D\.|--+)|(\d+(?:\.\d+)?)\s*(%|ppm)?)""",
                RegexOption.IGNORE_CASE
            )
            inlineRegex.findAll(cleanText).forEach { match ->
                val rawSym = match.groupValues[1].uppercase(Locale.US)
                val canonical = canonicalByUpper[rawSym] ?: return@forEach
                val lodToken = match.groupValues[2]
                val numStr = match.groupValues[3]
                val unit = match.groupValues[4].lowercase(Locale.US)

                if (lodToken.isNotBlank()) return@forEach
                val parsed = numStr.toDoubleOrNull() ?: return@forEach
                val pct = if (unit == "ppm") parsed / 10_000.0 else parsed
                if (pct in 0.0..100.0 && !elements.containsKey(canonical)) {
                    elements[canonical] = pct
                }
            }

            // Pattern B: vertical pairing where symbol is alone on line i and value is alone on line i+1
            val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val pureSymRegex = Regex("""^($symbolAlternation)\s*[:=]?$""", RegexOption.IGNORE_CASE)
            val pureValRegex = Regex("""^(\d+(?:\.\d+)?)\s*(%|ppm)?$""", RegexOption.IGNORE_CASE)
            for (i in 0 until lines.lastIndex) {
                val symMatch = pureSymRegex.matchEntire(lines[i]) ?: continue
                val valMatch = pureValRegex.matchEntire(lines[i + 1]) ?: continue
                val canonical = canonicalByUpper[symMatch.groupValues[1].uppercase(Locale.US)] ?: continue
                if (elements.containsKey(canonical)) continue
                val parsed = valMatch.groupValues[1].toDoubleOrNull() ?: continue
                val unit = valMatch.groupValues[2].lowercase(Locale.US)
                val pct = if (unit == "ppm") parsed / 10_000.0 else parsed
                if (pct in 0.0..100.0) {
                    elements[canonical] = pct
                }
            }
        }

        val detectedGrade = extractGrade(cleanText)
        val isNonMetal = nonMetalKeywords.any { cleanText.contains(it, ignoreCase = true) } && elements.isEmpty()
        val materialType = if (isNonMetal) "非金属" else "金属"

        val summaryParts = buildList {
            if (isNonMetal) {
                add("离线 OCR 判定为非金属材料")
            } else if (elements.isNotEmpty()) {
                val top = elements.entries.take(5).joinToString("，") { (k, v) ->
                    "$k=${String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')}%"
                }
                add("离线 OCR 识别到 ${elements.size} 项元素（$top）")
            } else {
                add("离线 OCR 未匹配到标准元素百分比")
            }
            if (detectedGrade.isNotBlank()) {
                add("牌号：$detectedGrade")
            }
        }

        return SpectrometerOcrResult(
            materialType = materialType,
            detectedGrade = detectedGrade,
            elements = elements,
            rawOcrSummary = summaryParts.joinToString("；")
        )
    }

    private fun extractGrade(text: String): String {
        for (pattern in gradePatterns) {
            val found = pattern.find(text)?.value
            if (!found.isNullOrBlank()) {
                return found.replace("\\s+".toRegex(), "").uppercase(Locale.US)
            }
        }
        // Check explicit "牌号: XXX" or "Grade: XXX"
        val labelMatch = Regex("""(?:牌号|材质|Alloy|Grade)\s*[:=]?\s*([A-Za-z0-9#-]{2,14})""", RegexOption.IGNORE_CASE)
            .find(text)
        return labelMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}

/**
 * 100% On-Device Offline OCR repository powered by bundled ML Kit Chinese+Latin Text Recognition.
 * Zero network calls, zero internet permission required.
 */
class SpectrometerOcrRepository {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognizeSpectrometerPhoto(
        context: Context,
        imageSource: Uri,
        localFilePath: String,
        partName: String,
        targetElements: List<String>
    ): Result<SpectrometerOcrResult> {
        return try {
            val bitmap = withContext(Dispatchers.IO) {
                if (localFilePath.isNotBlank() && File(localFilePath).exists()) {
                    decodeFileScaled(localFilePath, maxDimension = 1600)
                } else {
                    decodeUriScaled(context, imageSource, maxDimension = 1600)
                }
            } ?: return Result.failure(IllegalArgumentException("无法读取照片文件，请重试。"))

            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = recognizer.process(inputImage).awaitResult()

                val spatialLines = mutableListOf<OcrSpatialLine>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox
                        if (box != null) {
                            spatialLines += OcrSpatialLine(
                                text = line.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom
                            )
                        } else {
                            spatialLines += OcrSpatialLine(line.text, 0, spatialLines.size * 30, 100, spatialLines.size * 30 + 24)
                        }
                    }
                }

                val parsed = if (spatialLines.isNotEmpty()) {
                    SpectrometerTextParser.parseFromSpatialLines(spatialLines, targetElements)
                } else {
                    SpectrometerTextParser.parsePlainText(visionText.text, targetElements)
                }

                val enriched = if (parsed.elements.isEmpty() && visionText.text.isNotBlank()) {
                    val snippet = visionText.text.replace("\n", " / ").take(80)
                    parsed.copy(rawOcrSummary = "${parsed.rawOcrSummary}（原始识别：$snippet）")
                } else {
                    parsed
                }

                Log.d(TAG, "Offline OCR [$partName]: ${enriched.rawOcrSummary}")
                Result.success(enriched)
            } finally {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Offline OCR failed", e)
            Result.failure(Exception(e.localizedMessage ?: "本地 OCR 识别失败，请检查光线或手动输入。", e))
        }
    }

    private fun decodeFileScaled(path: String, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w / sample, h / sample) > maxDimension) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun decodeUriScaled(context: Context, uri: Uri, maxDimension: Int): Bitmap? {
        val resolver = context.contentResolver
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }
        val rawWidth = boundsOptions.outWidth
        val rawHeight = boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while (max(rawWidth / sampleSize, rawHeight / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> cont.resume(result) }
        addOnFailureListener { e -> cont.resumeWithException(e) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val TAG = "SpectrometerOcrRepo"
    }
}
