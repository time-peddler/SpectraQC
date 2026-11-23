package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.example.model.ELEMENT_SYMBOLS
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
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
import kotlin.math.min

data class SpectrometerOcrResult(
    val materialType: String,
    val detectedGrade: String,
    val elements: Map<String, Double>,
    val rawOcrSummary: String,
    val detectedLightElementPct: Double? = null
)

data class OcrSpatialLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/**
 * Pure Kotlin parser for offline XRF/OES spectrometer OCR text.
 * Tuned for handheld analyzers (Olympus Vanta, Thermo Niton, Bruker, Hitachi) including:
 * - Multi-column tabular screens: [El | % | +/- 3sigma | Match/Bar]
 * - Skewed / tilted screens via 2D column clustering and nearest-Y matching
 * - OCR character confusion recovery (O->0, comma->dot, Nl->Ni, T1->Ti, [Ni]->Ni)
 * - Light-element (LE) reporting, <LOD/ND exclusion, ppm->% conversion, and grade voting
 */
object SpectrometerTextParser {

    private val gradePatterns = listOf(
        Regex("""\b(SS\s*304L?|304L?|SS\s*316L?|316L?|316Ti|321|310S|309S|2205|2507|17-4PH)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(WCB|WC6|WC9|LCC|CF8M?|CF3M?|A105|A182|F304|F316|F22)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(Q235[A-D]?|Q345[A-E]?|Q355[A-E]?|20#|45#|35CrMo|42CrMo|16Mn|HT200|QT450|QT500)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(H62|H59|HPb59-1|T2|CuZn\d+|ZCuSn\d+|1060|2024|3003|5052|5083|6061|6063|7075)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(PTFE|PFA|EPDM|FKM|NBR|PPL|PEEK|POM|NYLON)\b""", RegexOption.IGNORE_CASE)
    )

    private val nonMetalKeywords = listOf(
        "PTFE", "聚四氟", "四氟", "非金属", "橡胶", "塑料", "EPDM", "FKM", "NBR", "PPL", "无法激发", "NON-METAL"
    )

    // Common OCR misreads for chemical element symbols on LCD spectrometer screens
    private val elementOcrAliases = mapOf(
        "NL" to "Ni", "N1" to "Ni", "NI" to "Ni", "MI" to "Ni",
        "FC" to "Fe", "F0" to "Fe", "FE" to "Fe",
        "GR" to "Cr", "CR" to "Cr",
        "GU" to "Cu", "CU" to "Cu",
        "T1" to "Ti", "TL" to "Ti", "TI" to "Ti",
        "NN" to "Mn", "MN" to "Mn",
        "2N" to "Zn", "ZN" to "Zn",
        "M0" to "Mo", "MO" to "Mo",
        "S1" to "Si", "SL" to "Si", "SI" to "Si",
        "A1" to "Al", "AL" to "Al",
        "C0" to "Co", "CO" to "Co"
    )

    private data class ParsedNumericToken(
        val value: Double?,
        val isNd: Boolean,
        val isErrorRangeToken: Boolean,
        val line: OcrSpatialLine
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
                val tolerance = max(18.0, minHeight * 0.85)
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
        if (lines.isEmpty()) {
            return parsePlainText("", targetElements)
        }

        val normalizedLines = lines.map { line ->
            line.copy(text = normalizeOcrLineText(line.text))
        }.filter { it.text.isNotBlank() }

        val canonicalByUpper = targetElements.associateBy { it.uppercase(Locale.US) }
        val elements = linkedMapOf<String, Double>()
        var lightElementPct: Double? = null

        // Stage A: 2D Spatial Column-Row Pairing (handles Olympus Vanta El | % | +/- 3sigma | Bar & tilted screens)
        val elAnchors = mutableListOf<Pair<String, OcrSpatialLine>>() // "LE" or canonical symbol -> line
        val candidateValues = mutableListOf<ParsedNumericToken>()

        // Detect optional header positions to tighten column filtering
        val pctHeader = normalizedLines.firstOrNull {
            val t = it.text.trim()
            t == "%" || t == "% v" || t == "%✓" || t.startsWith("%")
        }
        val sigmaHeader = normalizedLines.firstOrNull {
            val t = it.text.lowercase(Locale.US)
            t.contains("+/-") || t.contains("3σ") || t.contains("3o")
        }

        for (line in normalizedLines) {
            val trimmed = line.text.trim()
            // Check standalone element token (e.g. "Fe", "[Ni]", "LE")
            val symCandidate = resolveStandaloneElementOrLe(trimmed, canonicalByUpper)
            if (symCandidate != null) {
                elAnchors += symCandidate to line
                continue
            }

            // Check standalone numeric or ND token
            val parsedVal = parseStandaloneValueToken(trimmed)
            if (parsedVal != null) {
                candidateValues += ParsedNumericToken(
                    value = parsedVal.first,
                    isNd = parsedVal.second,
                    isErrorRangeToken = trimmed.startsWith("<"),
                    line = line
                )
            }
        }

        if (elAnchors.isNotEmpty() && candidateValues.isNotEmpty()) {
            // Restrict x range if "%" and "+/-" headers are both cleanly detected
            val minValX = pctHeader?.let { it.left - it.width } ?: Int.MIN_VALUE
            val maxValX = sigmaHeader?.let { it.left + (it.width / 4) } ?: Int.MAX_VALUE

            // Sort anchors top-to-bottom
            val sortedAnchors = elAnchors.sortedBy { it.second.centerY }
            val usedValueLines = hashSetOf<OcrSpatialLine>()

            // Estimate row tilt dy/dx if possible, or match nearest-right value column
            for ((sym, symLine) in sortedAnchors) {
                if (sym != "LE" && elements.containsKey(sym)) continue

                // Values must be to the right of the element column and strictly left of +/- 3sigma header if present
                val rowTolerance = max(28.0, symLine.height * 1.45)
                val rightCandidates = candidateValues.filter { cand ->
                    cand.line !in usedValueLines &&
                        cand.line.centerX > symLine.right - (symLine.width / 3) &&
                        (sigmaHeader == null || cand.line.centerX < sigmaHeader.left) &&
                        abs(cand.line.centerY - symLine.centerY) <= rowTolerance
                }.sortedWith(
                    compareBy<ParsedNumericToken> { cand ->
                        // Prefer tokens inside [% column .. +/- column) if header detected
                        val inPctBand = cand.line.centerX in minValX..maxValX
                        if (inPctBand) 0 else 1
                    }.thenBy { cand ->
                        // Pick the LEFTMOST value column first (% column is always left of +/- 3sigma column!)
                        cand.line.left
                    }.thenBy { cand ->
                        abs(cand.line.centerY - symLine.centerY)
                    }
                )

                val best = rightCandidates.firstOrNull() ?: continue
                usedValueLines.add(best.line)
                if (!best.isNd && !best.isErrorRangeToken && best.value != null && best.value in 0.0..100.0) {
                    if (sym == "LE") {
                        if (lightElementPct == null) lightElementPct = best.value
                    } else {
                        elements[sym] = best.value
                    }
                }
            }
        }

        // Stage B: Reconstruct horizontal rows (excluding +/- 3sigma & tolerance bar columns below header) & run inline parsing
        val stageBLines = if (sigmaHeader != null) {
            normalizedLines.filter { line ->
                line.bottom <= sigmaHeader.bottom || line.centerX < sigmaHeader.left
            }
        } else {
            normalizedLines
        }
        val reconstructed = reconstructSpatialRows(stageBLines)
        val combinedText = reconstructed.joinToString("\n")
        val textParsed = parsePlainText(combinedText, targetElements)

        for ((k, v) in textParsed.elements) {
            if (!elements.containsKey(k)) {
                elements[k] = v
            }
        }
        if (lightElementPct == null) {
            lightElementPct = textParsed.detectedLightElementPct
        }

        val detectedGrade = extractBestGrade(normalizedLines.map { it.text }, combinedText)
        val isNonMetal = nonMetalKeywords.any { combinedText.contains(it, ignoreCase = true) } && elements.isEmpty()
        val materialType = if (isNonMetal) "非金属" else "金属"

        return SpectrometerOcrResult(
            materialType = materialType,
            detectedGrade = detectedGrade,
            elements = elements,
            rawOcrSummary = buildSummary(elements, detectedGrade, isNonMetal, lightElementPct),
            detectedLightElementPct = lightElementPct
        )
    }

    fun parsePlainText(
        rawText: String,
        targetElements: List<String> = ELEMENT_SYMBOLS
    ): SpectrometerOcrResult {
        val cleanText = normalizeOcrLineText(rawText).trim()

        if (cleanText.isBlank()) {
            return SpectrometerOcrResult(
                materialType = "金属",
                detectedGrade = "",
                elements = emptyMap(),
                rawOcrSummary = "未识别到清晰文字，请将屏幕放正并居中后重新拍照。"
            )
        }

        val canonicalByUpper = targetElements.associateBy { it.uppercase(Locale.US) }
        val symbolAlternation = (canonicalByUpper.keys + listOf("LE"))
            .distinct()
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }

        val elements = linkedMapOf<String, Double>()
        var lightElementPct: Double? = null

        if (symbolAlternation.isNotBlank()) {
            // Recover inline OCR element symbol typos (e.g. "Nl 0.132", "T1 ND") before regex matching
            val aliasCorrectedText = cleanText
                .replace(Regex("""(?<![A-Za-z0-9])(Nl|N1|MI)(?=\s*[:=]?\s*(?:\d|ND|<LOD))""", RegexOption.IGNORE_CASE), "Ni")
                .replace(Regex("""(?<![A-Za-z0-9])(T1|TL)(?=\s*[:=]?\s*(?:\d|ND|<LOD))""", RegexOption.IGNORE_CASE), "Ti")
                .replace(Regex("""(?<![A-Za-z0-9])(FC|F0)(?=\s*[:=]?\s*(?:\d|ND|<LOD))""", RegexOption.IGNORE_CASE), "Fe")

            // Pattern A: same-line symbol + value, e.g. "Fe 0.198 0.083", "Cr: 18.2%", "Cu 180ppm", "Ti ND <0.14"
            // Captures ONLY the FIRST token after symbol so +/- 3sigma column isn't mixed in!
            val inlineRegex = Regex(
                """(?<![A-Za-z0-9])($symbolAlternation)\s*[:=]?\s*(?:(<LOD|ND|N\.D\.|--+)|(\d+(?:\.\d+)?)\s*(%|ppm)?)""",
                RegexOption.IGNORE_CASE
            )
            inlineRegex.findAll(aliasCorrectedText).forEach { match ->
                val rawSym = match.groupValues[1].uppercase(Locale.US)
                val lodToken = match.groupValues[2]
                val numStr = match.groupValues[3]
                val unit = match.groupValues[4].lowercase(Locale.US)

                if (lodToken.isNotBlank()) return@forEach
                val parsed = numStr.toDoubleOrNull() ?: return@forEach
                val pct = if (unit == "ppm") parsed / 10_000.0 else parsed
                if (pct in 0.0..100.0) {
                    if (rawSym == "LE") {
                        if (lightElementPct == null) lightElementPct = pct
                    } else {
                        val canonical = canonicalByUpper[rawSym] ?: return@forEach
                        if (!elements.containsKey(canonical)) {
                            elements[canonical] = pct
                        }
                    }
                }
            }

            // Pattern B: vertical pairing where symbol is alone on line i and value is alone on line i+1
            val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (i in 0 until lines.lastIndex) {
                val sym = resolveStandaloneElementOrLe(lines[i], canonicalByUpper) ?: continue
                val valPair = parseStandaloneValueToken(lines[i + 1]) ?: continue
                if (!valPair.second && valPair.first != null && valPair.first!! in 0.0..100.0) {
                    if (sym == "LE") {
                        if (lightElementPct == null) lightElementPct = valPair.first
                    } else if (!elements.containsKey(sym)) {
                        elements[sym] = valPair.first!!
                    }
                }
            }
        }

        val detectedGrade = extractBestGrade(cleanText.lines(), cleanText)
        val isNonMetal = nonMetalKeywords.any { cleanText.contains(it, ignoreCase = true) } && elements.isEmpty()
        val materialType = if (isNonMetal) "非金属" else "金属"

        return SpectrometerOcrResult(
            materialType = materialType,
            detectedGrade = detectedGrade,
            elements = elements,
            rawOcrSummary = buildSummary(elements, detectedGrade, isNonMetal, lightElementPct),
            detectedLightElementPct = lightElementPct
        )
    }

    internal fun normalizeOcrLineText(input: String): String {
        var text = input
            .replace('％', '%')
            .replace('：', ':')
            .replace('＝', '=')
            .replace('，', ',')
        // Fix common O -> 0 before decimal separator and normalize decimal commas/spaces ("0,147" / "0 . 198")
        text = text.replace(Regex("""(?<![A-Za-z])[Oo](?=\s*[.,]\s*\d)"""), "0")
        text = text.replace(Regex("""(\d)\s*[.,]\s*(\d)"""), "$1.$2")
        // Strip brackets/pipes/stray commas after numeric decimal points are preserved
        text = text.replace(Regex("""[,{}\[\]()|]"""), " ")
        return text.trim()
    }

    private fun resolveStandaloneElementOrLe(
        token: String,
        canonicalByUpper: Map<String, String>
    ): String? {
        val cleaned = token.replace(Regex("""[^A-Za-z0-9]"""), "").uppercase(Locale.US)
        if (cleaned == "LE") return "LE"
        canonicalByUpper[cleaned]?.let { return it }
        elementOcrAliases[cleaned]?.let { aliased ->
            canonicalByUpper[aliased.uppercase(Locale.US)]?.let { return it }
        }
        return null
    }

    private fun parseStandaloneValueToken(token: String): Pair<Double?, Boolean>? {
        val t = token.trim()
        if (t.equals("ND", ignoreCase = true) ||
            t.equals("N.D.", ignoreCase = true) ||
            t.equals("<LOD", ignoreCase = true) ||
            t.startsWith("<")
        ) {
            return null to true
        }
        val match = Regex("""^(\d+(?:\.\d+)?)\s*(%|ppm)?$""", RegexOption.IGNORE_CASE).matchEntire(t)
            ?: return null
        val parsed = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase(Locale.US)
        val pct = if (unit == "ppm") parsed / 10_000.0 else parsed
        return pct to false
    }

    private fun extractBestGrade(lines: List<String>, fullText: String): String {
        val votes = linkedMapOf<String, Int>()
        for (line in lines) {
            // Strip library suffix like "5052-plus" so it doesn't outvote actual matched grade "6061"
            val scrubbed = line.replace(Regex("""\b[A-Za-z0-9]+-plus\b""", RegexOption.IGNORE_CASE), "")
            val isStandalone = scrubbed.trim().matches(Regex("""^[A-Za-z0-9#-]{2,12}$"""))
            val weight = if (isStandalone) 3 else 1
            for (pattern in gradePatterns) {
                pattern.findAll(scrubbed).forEach { m ->
                    val normalized = m.value.replace(Regex("""\s+"""), "").uppercase(Locale.US)
                    votes[normalized] = (votes[normalized] ?: 0) + weight
                }
            }
        }
        votes.maxByOrNull { it.value }?.key?.let { return it }

        val labelMatch = Regex("""(?:牌号|材质|Alloy|Grade|合金)\s*[:=]?\s*([A-Za-z0-9#-]{2,14})""", RegexOption.IGNORE_CASE)
            .find(fullText)
        return labelMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun buildSummary(
        elements: Map<String, Double>,
        grade: String,
        isNonMetal: Boolean,
        lightElementPct: Double?
    ): String {
        val parts = buildList {
            if (isNonMetal) {
                add("离线 OCR 判定为非金属材料")
            } else if (elements.isNotEmpty()) {
                val top = elements.entries.take(5).joinToString("，") { (k, v) ->
                    "$k=${String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')}%"
                }
                add("离线 OCR 识别到 ${elements.size} 项元素（$top）")
            } else {
                add("离线 OCR 未匹配到标准元素百分比")
            }
            if (lightElementPct != null) {
                val leStr = String.format(Locale.US, "%.2f", lightElementPct).trimEnd('0').trimEnd('.')
                add("轻元素 LE=$leStr%")
            }
            if (grade.isNotBlank()) {
                add("牌号：$grade")
            }
        }
        return parts.joinToString("；")
    }
}

/**
 * 100% On-Device Offline OCR repository powered by bundled ML Kit Chinese+Latin Text Recognition.
 * Features high-resolution decoding, bright LCD screen ROI cropping + contrast boost,
 * and automatic 4-orientation rotation sweep (0°, 90°, 270°, 180°) for sideways/tilted captures.
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
            val baseBitmap = withContext(Dispatchers.IO) {
                if (localFilePath.isNotBlank() && File(localFilePath).exists()) {
                    decodeFileScaled(localFilePath, maxDimension = 2560)
                } else {
                    decodeUriScaled(context, imageSource, maxDimension = 2560)
                }
            } ?: return Result.failure(IllegalArgumentException("无法读取照片文件，请重试。"))

            try {
                // Stage 1: Full-frame sweep (0°, then 90°/270°/180° if needed)
                var bestAttempt = runMultiOrientationSweep(baseBitmap, targetElements)

                // Stage 2: If fewer than 2 elements found, crop bright LCD screen ROI, boost contrast, and re-sweep
                if (bestAttempt.result.elements.size < 2) {
                    val lcdBitmap = withContext(Dispatchers.Default) {
                        extractEnhancedLcdRegion(baseBitmap)
                    }
                    if (lcdBitmap != null) {
                        try {
                            val lcdAttempt = runMultiOrientationSweep(lcdBitmap, targetElements)
                            if (lcdAttempt.score > bestAttempt.score) {
                                bestAttempt = lcdAttempt
                            }
                        } finally {
                            if (!lcdBitmap.isRecycled) lcdBitmap.recycle()
                        }
                    }
                }

                val parsed = bestAttempt.result
                val finalResult = if (parsed.elements.isEmpty() && bestAttempt.rawSnippet.isNotBlank()) {
                    parsed.copy(
                        rawOcrSummary = "${parsed.rawOcrSummary}（原始识别：${bestAttempt.rawSnippet.take(70)}）"
                    )
                } else {
                    parsed
                }

                Log.d(TAG, "Offline OCR [$partName] rot=${bestAttempt.rotation} score=${bestAttempt.score}: ${finalResult.rawOcrSummary}")
                Result.success(finalResult)
            } finally {
                if (!baseBitmap.isRecycled) {
                    baseBitmap.recycle()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Offline OCR failed", e)
            Result.failure(Exception(e.localizedMessage ?: "本地 OCR 识别失败，请检查光线或手动输入。", e))
        }
    }

    private data class OcrSweepAttempt(
        val result: SpectrometerOcrResult,
        val score: Int,
        val rotation: Int,
        val rawSnippet: String
    )

    private suspend fun runMultiOrientationSweep(
        source: Bitmap,
        targetElements: List<String>
    ): OcrSweepAttempt {
        val rotations = listOf(0, 90, 270, 180)
        var best: OcrSweepAttempt? = null

        for (deg in rotations) {
            val rotated = if (deg == 0) source else rotateBitmap(source, deg.toFloat())
            try {
                val inputImage = InputImage.fromBitmap(rotated, 0)
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

                val hasTableCues = visionText.text.contains("LE", ignoreCase = true) ||
                    visionText.text.contains("ND", ignoreCase = true) ||
                    visionText.text.contains("%")
                val score = parsed.elements.size * 10 +
                    (if (parsed.detectedGrade.isNotBlank()) 4 else 0) +
                    (if (parsed.detectedLightElementPct != null) 4 else 0) +
                    (if (hasTableCues) 3 else 0) +
                    spatialLines.size.coerceAtMost(5)

                val candidate = OcrSweepAttempt(
                    result = parsed,
                    score = score,
                    rotation = deg,
                    rawSnippet = visionText.text.replace("\n", " / ")
                )
                if (best == null || candidate.score > best.score) {
                    best = candidate
                }

                // Short-circuit immediately once a clear spectrometer table (>= 3 elements) is extracted
                if (parsed.elements.size >= 3 && (parsed.detectedGrade.isNotBlank() || parsed.detectedLightElementPct != null)) {
                    break
                }
            } finally {
                if (deg != 0 && rotated !== source && !rotated.isRecycled) {
                    rotated.recycle()
                }
            }
        }

        return best ?: OcrSweepAttempt(
            result = SpectrometerTextParser.parsePlainText("", targetElements),
            score = 0,
            rotation = 0,
            rawSnippet = ""
        )
    }

    /**
     * Locates the bright white/gray spectrometer LCD screen inside a photo, crops it with padding,
     * upscales if needed, and applies mild contrast enhancement for crisp digit OCR.
     */
    private fun extractEnhancedLcdRegion(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height
        if (w < 100 || h < 100) return null

        val gridW = 160
        val gridH = ((h.toFloat() / w.toFloat()) * gridW).toInt().coerceIn(60, 200)
        val small = Bitmap.createScaledBitmap(source, gridW, gridH, true)
        val pixels = IntArray(gridW * gridH)
        small.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        small.recycle()

        // Find bright neutral/white LCD pixels (high luminance, low color saturation)
        val brightMask = BooleanArray(gridW * gridH)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val luma = (r * 3 + g * 6 + b) / 10
            if (luma >= 170 && (maxC - minC) <= 55) {
                brightMask[i] = true
            }
        }

        // Isolate the largest 4-connected bright component so metallic valve highlights don't expand the crop
        val visited = BooleanArray(gridW * gridH)
        val queue = IntArray(gridW * gridH)
        var bestArea = 0
        var bestMinX = 0
        var bestMaxX = -1
        var bestMinY = 0
        var bestMaxY = -1

        for (startIdx in pixels.indices) {
            if (!brightMask[startIdx] || visited[startIdx]) continue
            var head = 0
            var tail = 0
            queue[tail++] = startIdx
            visited[startIdx] = true

            var area = 0
            var minX = gridW
            var maxX = 0
            var minY = gridH
            var maxY = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % gridW
                val y = idx / gridW
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y

                if (x > 0) {
                    val left = idx - 1
                    if (brightMask[left] && !visited[left]) {
                        visited[left] = true
                        queue[tail++] = left
                    }
                }
                if (x + 1 < gridW) {
                    val right = idx + 1
                    if (brightMask[right] && !visited[right]) {
                        visited[right] = true
                        queue[tail++] = right
                    }
                }
                if (y > 0) {
                    val up = idx - gridW
                    if (brightMask[up] && !visited[up]) {
                        visited[up] = true
                        queue[tail++] = up
                    }
                }
                if (y + 1 < gridH) {
                    val down = idx + gridW
                    if (brightMask[down] && !visited[down]) {
                        visited[down] = true
                        queue[tail++] = down
                    }
                }
            }

            if (area > bestArea) {
                bestArea = area
                bestMinX = minX
                bestMaxX = maxX
                bestMinY = minY
                bestMaxY = maxY
            }
        }

        val compW = bestMaxX - bestMinX + 1
        val compH = bestMaxY - bestMinY + 1
        if (bestArea < (gridW * gridH * 0.02f).toInt() || compW < gridW * 0.12f || compH < gridH * 0.10f) {
            return null
        }

        // Pad upward slightly more (22%) to keep the blue Alloy/Grade header bar above the white table
        val padX = (compW * 0.08f).toInt().coerceAtLeast(2)
        val padTop = (compH * 0.22f).toInt().coerceAtLeast(4)
        val padBottom = (compH * 0.08f).toInt().coerceAtLeast(2)

        val cropLeft = (((bestMinX - padX).coerceAtLeast(0).toFloat() / gridW) * w).toInt()
        val cropTop = (((bestMinY - padTop).coerceAtLeast(0).toFloat() / gridH) * h).toInt()
        val cropRight = (((bestMaxX + padX).coerceAtMost(gridW - 1).toFloat() / gridW) * w).toInt()
        val cropBottom = (((bestMaxY + padBottom).coerceAtMost(gridH - 1).toFloat() / gridH) * h).toInt()

        val cropW = (cropRight - cropLeft).coerceAtLeast(1)
        val cropH = (cropBottom - cropTop).coerceAtLeast(1)
        if (cropW < 100 || cropH < 100) return null

        val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, cropW, cropH)
        val maxSide = max(cropW, cropH)
        val targetBitmap = if (maxSide < 1400) {
            val scale = 1400f / maxSide.toFloat()
            val upscaled = Bitmap.createScaledBitmap(
                cropped,
                (cropW * scale).toInt(),
                (cropH * scale).toInt(),
                true
            )
            cropped.recycle()
            upscaled
        } else {
            cropped
        }

        // Apply mild contrast boost for crisp digits on LCD photos
        val enhanced = Bitmap.createBitmap(targetBitmap.width, targetBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val contrast = 1.25f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(targetBitmap, 0f, 0f, paint)
        targetBitmap.recycle()
        return enhanced
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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
