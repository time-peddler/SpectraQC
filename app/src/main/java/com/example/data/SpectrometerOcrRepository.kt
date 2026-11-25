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
    val detectedLightElementPct: Double? = null,
    val ndElements: Set<String> = emptySet()
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

    private data class DeskewedLine<T>(
        val item: T,
        val u: Double,
        val v: Double,
        val uLeft: Double,
        val uRight: Double,
        val vTop: Double,
        val vBottom: Double
    )

    private data class ColumnCluster(
        var centerU: Double,
        val members: MutableList<DeskewedLine<ParsedNumericToken>>
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
        val ndElements = linkedSetOf<String>()
        var lightElementPct: Double? = null

        val elAnchors = mutableListOf<Pair<String, OcrSpatialLine>>()
        val candidateValues = mutableListOf<ParsedNumericToken>()

        val pctHeader = normalizedLines.firstOrNull {
            val t = it.text.trim()
            t == "%" || t == "% v" || t == "%✓" || (t.startsWith("%") && t.length <= 5)
        }
        val sigmaHeader = normalizedLines.firstOrNull { line ->
            val t = line.text.trim().lowercase(Locale.US)
            t.contains("+/-") || t.contains("±") || t.contains("3σ") || t.contains("3o") ||
                (pctHeader != null && (t == "30" || t == "36" || t == "+-30") &&
                    abs(line.centerY - pctHeader.centerY) <= max(24, pctHeader.height * 2) &&
                    line.centerX > pctHeader.right)
        }

        for (line in normalizedLines) {
            val trimmed = line.text.trim()
            val symCandidate = resolveStandaloneElementOrLe(trimmed, canonicalByUpper)
            if (symCandidate != null) {
                elAnchors += symCandidate to line
                continue
            }

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

        val medianHeight = normalizedLines.map { it.height.toDouble() }.sorted()
            .let { if (it.isEmpty()) 24.0 else it[it.size / 2] }.coerceAtLeast(14.0)

        // Identify if anchors form a single fixed vertical El column (e.g. Olympus Vanta / Niton / Bruker)
        val primaryElColumn = selectPrimaryVerticalElColumn(elAnchors, medianHeight)
        val isSingleElColumnTable = primaryElColumn.size >= 2 && candidateValues.isNotEmpty()

        var lockedPctColumnMaxU: Double? = null
        var cosT = 1.0
        var sinT = 0.0

        if (isSingleElColumnTable) {
            // 1. Estimate screen tilt angle from vertical El column (dx = slopeX * dy) and deskew coordinates
            val sortedRawAnchors = primaryElColumn.sortedBy { it.second.centerY }
            val meanX = sortedRawAnchors.map { it.second.centerX.toDouble() }.average()
            val meanY = sortedRawAnchors.map { it.second.centerY.toDouble() }.average()
            var num = 0.0
            var den = 0.0
            for ((_, ln) in sortedRawAnchors) {
                val dy = ln.centerY - meanY
                val dx = ln.centerX - meanX
                num += dy * dx
                den += dy * dy
            }
            val slopeX = if (den > 100.0) (num / den).coerceIn(-0.35, 0.35) else 0.0
            val theta = kotlin.math.atan(slopeX)
            cosT = kotlin.math.cos(theta)
            sinT = kotlin.math.sin(theta)

            fun <T> deskew(item: T, line: OcrSpatialLine): DeskewedLine<T> {
                val cx = line.centerX.toDouble()
                val cy = line.centerY.toDouble()
                val u = cx * cosT - cy * sinT
                val v = cx * sinT + cy * cosT
                val hw = line.width / 2.0
                val hh = line.height / 2.0
                return DeskewedLine(item, u, v, u - hw, u + hw, v - hh, v + hh)
            }

            val deskewedAnchors = sortedRawAnchors.map { deskew(it, it.second) }.sortedBy { it.v }
            val deskewedValues = candidateValues.map { deskew(it, it.line) }
            val pctHeaderUV = pctHeader?.let { deskew(it, it) }
            val sigmaHeaderUV = sigmaHeader?.let { deskew(it, it) }

            val elRightU = deskewedAnchors.map { it.uRight }.sorted()[deskewedAnchors.size / 2]
            val rightValues = deskewedValues.filter { it.u > elRightU + medianHeight * 0.25 }

            // 2. Cluster value tokens vertically by deskewed U to isolate the single "%" column
            val colClusterTol = max(26.0, medianHeight * 1.45)
            val clusters = mutableListOf<ColumnCluster>()
            for (tok in rightValues.sortedBy { it.u }) {
                val match = clusters.minByOrNull { abs(tok.u - it.centerU) }
                if (match != null && abs(tok.u - match.centerU) <= colClusterTol) {
                    match.members += tok
                    match.centerU = match.members.map { it.u }.average()
                } else {
                    clusters += ColumnCluster(tok.u, mutableListOf(tok))
                }
            }

            // Pick the "%" column cluster (leftmost valid value column, strictly left of "+/- 3sigma")
            val validClusters = clusters.filter { cluster ->
                val alignedCount = deskewedAnchors.count { anchor ->
                    cluster.members.any { abs(it.v - anchor.v) <= medianHeight * 1.4 }
                }
                alignedCount >= 1
            }

            val pctCluster = selectPctColumnCluster(
                clusters = validClusters,
                pctHeaderUV = pctHeaderUV,
                sigmaHeaderUV = sigmaHeaderUV,
                medianHeight = medianHeight
            )

            if (pctCluster != null) {
                val nextRight = validClusters
                    .filter { it.centerU > pctCluster.centerU + medianHeight * 1.4 }
                    .minByOrNull { it.centerU }

                val halfLeft = max(34.0, medianHeight * 2.1)
                val halfRight = if (nextRight != null) {
                    min((nextRight.centerU - pctCluster.centerU) * 0.45, max(36.0, medianHeight * 2.1))
                } else {
                    max(36.0, medianHeight * 2.1)
                }
                val minU = pctCluster.centerU - halfLeft
                val maxU = if (sigmaHeaderUV != null) {
                    min(pctCluster.centerU + halfRight, sigmaHeaderUV.u - medianHeight * 0.35)
                } else {
                    pctCluster.centerU + halfRight
                }
                lockedPctColumnMaxU = maxU

                val pctCandidates = rightValues.filter { it.u in minU..maxU }

                // 3. Strict half-pitch row matching to prevent row-to-row cascading shifts
                val rowGaps = deskewedAnchors.zipWithNext { a, b -> b.v - a.v }
                    .filter { it in (medianHeight * 1.05)..(medianHeight * 4.5) }
                    .sorted()
                val rowPitch = if (rowGaps.isNotEmpty()) rowGaps[rowGaps.size / 2] else 0.0
                val strictRowTol = if (rowPitch > 0.0) {
                    min(rowPitch * 0.43, max(16.0, medianHeight * 1.0))
                } else {
                    max(18.0, medianHeight * 1.0)
                }

                // Measure tiny systematic V offset between El and % column
                val vDiffs = deskewedAnchors.mapNotNull { anchor ->
                    val nearest = pctCandidates.minByOrNull { abs(it.v - anchor.v) } ?: return@mapNotNull null
                    val d = nearest.v - anchor.v
                    if (abs(d) <= strictRowTol * 1.25) d else null
                }.sorted()
                val systematicDv = if (vDiffs.size >= 2) {
                    vDiffs[vDiffs.size / 2].coerceIn(-medianHeight * 0.65, medianHeight * 0.65)
                } else {
                    0.0
                }

                val usedPctTokens = hashSetOf<OcrSpatialLine>()
                for (anchor in deskewedAnchors) {
                    val sym = anchor.item.first
                    if (sym != "LE" && elements.containsKey(sym)) continue

                    val expectedV = anchor.v + systematicDv
                    val best = pctCandidates
                        .filter { it.item.line !in usedPctTokens && abs(it.v - expectedV) <= strictRowTol }
                        .minByOrNull { abs(it.v - expectedV) }
                        ?: continue

                    usedPctTokens.add(best.item.line)
                    val tok = best.item
                    if (tok.isNd) {
                        if (sym != "LE" && !elements.containsKey(sym)) {
                            ndElements.add(sym)
                        }
                    } else if (tok.value != null && tok.value in 0.0..100.0) {
                        if (sym == "LE") {
                            if (lightElementPct == null) lightElementPct = tok.value
                        } else {
                            elements[sym] = tok.value
                            ndElements.remove(sym)
                        }
                    }
                }
            }
        } else if (elAnchors.isNotEmpty() && candidateValues.isNotEmpty()) {
            // Fallback for multi-column key-value layouts (e.g. "Fe 70.42%   Cr 18.15")
            val minValX = pctHeader?.let { it.left - it.width } ?: Int.MIN_VALUE
            val maxValX = sigmaHeader?.let { it.left + (it.width / 4) } ?: Int.MAX_VALUE
            val sortedAnchors = elAnchors.sortedBy { it.second.centerY }
            val usedValueLines = hashSetOf<OcrSpatialLine>()

            for ((sym, symLine) in sortedAnchors) {
                if (sym != "LE" && elements.containsKey(sym)) continue
                val rowTolerance = max(24.0, symLine.height * 1.15)
                val best = candidateValues.filter { cand ->
                    cand.line !in usedValueLines &&
                        cand.line.centerX > symLine.right - (symLine.width / 3) &&
                        (sigmaHeader == null || cand.line.centerX < sigmaHeader.left) &&
                        abs(cand.line.centerY - symLine.centerY) <= rowTolerance
                }.sortedWith(
                    compareBy<ParsedNumericToken> { if (it.line.centerX in minValX..maxValX) 0 else 1 }
                        .thenBy { it.line.left }
                        .thenBy { abs(it.line.centerY - symLine.centerY) }
                ).firstOrNull() ?: continue

                usedValueLines.add(best.line)
                if (best.isNd && !best.isErrorRangeToken) {
                    if (sym != "LE" && !elements.containsKey(sym)) {
                        ndElements.add(sym)
                    }
                } else if (!best.isNd && !best.isErrorRangeToken && best.value != null && best.value in 0.0..100.0) {
                    if (sym == "LE") {
                        if (lightElementPct == null) lightElementPct = best.value
                    } else {
                        elements[sym] = best.value
                        ndElements.remove(sym)
                    }
                }
            }
        }

        // Stage B: Only parse inline lines strictly within the "%" column boundary (never +/- 3sigma),
        // and disable cross-line vertical fallback when a spectrometer column grid was already locked.
        val stageBLines = when {
            lockedPctColumnMaxU != null -> {
                normalizedLines.filter { line ->
                    val u = line.centerX * cosT - line.centerY * sinT
                    u <= lockedPctColumnMaxU
                }
            }
            sigmaHeader != null -> {
                normalizedLines.filter { line ->
                    line.bottom <= sigmaHeader.bottom || line.centerX < sigmaHeader.left
                }
            }
            else -> normalizedLines
        }
        val reconstructed = reconstructSpatialRows(stageBLines)
        val combinedText = reconstructed.joinToString("\n")
        val textParsed = parsePlainText(
            rawText = combinedText,
            targetElements = targetElements,
            allowVerticalPairFallback = lockedPctColumnMaxU == null
        )

        for ((k, v) in textParsed.elements) {
            if (!elements.containsKey(k)) {
                elements[k] = v
                ndElements.remove(k)
            }
        }
        for (ndSym in textParsed.ndElements) {
            if (!elements.containsKey(ndSym)) {
                ndElements.add(ndSym)
            }
        }
        if (lightElementPct == null) {
            lightElementPct = textParsed.detectedLightElementPct
        }

        val allText = reconstructSpatialRows(normalizedLines).joinToString("\n")
        val detectedGrade = extractBestGrade(normalizedLines.map { it.text }, allText)
        val isNonMetal = nonMetalKeywords.any { allText.contains(it, ignoreCase = true) } &&
            elements.isEmpty() && ndElements.isEmpty()
        val materialType = if (isNonMetal) "非金属" else "金属"

        return SpectrometerOcrResult(
            materialType = materialType,
            detectedGrade = detectedGrade,
            elements = elements,
            rawOcrSummary = buildSummary(elements, ndElements, detectedGrade, isNonMetal, lightElementPct),
            detectedLightElementPct = lightElementPct,
            ndElements = ndElements
        )
    }

    private fun selectPrimaryVerticalElColumn(
        elAnchors: List<Pair<String, OcrSpatialLine>>,
        medianHeight: Double
    ): List<Pair<String, OcrSpatialLine>> {
        if (elAnchors.size < 2) return emptyList()
        val xTol = max(32.0, medianHeight * 2.2)
        var bestCluster = emptyList<Pair<String, OcrSpatialLine>>()

        for (seed in elAnchors) {
            val col = elAnchors
                .filter { abs(it.second.centerX - seed.second.centerX) <= xTol }
                .sortedBy { it.second.centerY }
            // Ensure anchors in this cluster occupy distinct vertical rows (not horizontal pairs)
            val distinctRowCount = col.fold(mutableListOf<Pair<String, OcrSpatialLine>>()) { acc, item ->
                val last = acc.lastOrNull()
                if (last == null || item.second.centerY - last.second.centerY >= medianHeight * 0.7) {
                    acc += item
                }
                acc
            }
            if (distinctRowCount.size > bestCluster.size) {
                bestCluster = distinctRowCount
            }
        }
        return bestCluster
    }

    private fun selectPctColumnCluster(
        clusters: List<ColumnCluster>,
        pctHeaderUV: DeskewedLine<OcrSpatialLine>?,
        sigmaHeaderUV: DeskewedLine<OcrSpatialLine>?,
        medianHeight: Double
    ): ColumnCluster? {
        if (clusters.isEmpty()) return null
        val sorted = clusters.sortedBy { it.centerU }

        val leftOfSigma = if (sigmaHeaderUV != null) {
            sorted.filter { it.centerU < sigmaHeaderUV.u - medianHeight * 0.4 }.ifEmpty { sorted }
        } else {
            sorted
        }

        if (pctHeaderUV != null) {
            val nearHeader = leftOfSigma.minByOrNull { abs(it.centerU - pctHeaderUV.u) }
            if (nearHeader != null && abs(nearHeader.centerU - pctHeaderUV.u) <= max(60.0, medianHeight * 3.5)) {
                return nearHeader
            }
        }

        // Prefer the leftmost cluster with >= 2 tokens that isn't mostly "<LOD" error tokens
        return leftOfSigma.firstOrNull { cluster ->
            val errCount = cluster.members.count { it.item.isErrorRangeToken }
            cluster.members.size >= 2 && errCount * 2 < cluster.members.size
        } ?: leftOfSigma.firstOrNull { cluster ->
            cluster.members.any { !it.item.isErrorRangeToken }
        } ?: leftOfSigma.firstOrNull()
    }

    fun parsePlainText(
        rawText: String,
        targetElements: List<String> = ELEMENT_SYMBOLS,
        allowVerticalPairFallback: Boolean = true
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
        val ndElements = linkedSetOf<String>()
        var lightElementPct: Double? = null

        if (symbolAlternation.isNotBlank()) {
            // Recover inline OCR element symbol typos (e.g. "Nl 0.132", "T1 ND") before regex matching
            val aliasCorrectedText = cleanText
                .replace(Regex("""(?<![A-Za-z0-9])(Nl|N1|MI)(?=\s*[:=]?\s*(?:\d|ND|N\.?D|<LOD|无|未检出|N/?A|NONE|NIL|--))""", RegexOption.IGNORE_CASE), "Ni")
                .replace(Regex("""(?<![A-Za-z0-9])(T1|TL)(?=\s*[:=]?\s*(?:\d|ND|N\.?D|<LOD|无|未检出|N/?A|NONE|NIL|--))""", RegexOption.IGNORE_CASE), "Ti")
                .replace(Regex("""(?<![A-Za-z0-9])(FC|F0)(?=\s*[:=]?\s*(?:\d|ND|N\.?D|<LOD|无|未检出|N/?A|NONE|NIL|--))""", RegexOption.IGNORE_CASE), "Fe")

            // Pattern A: same-line symbol + value or ND/无 abbreviation
            // Captures ONLY the FIRST token after symbol so +/- 3sigma column isn't mixed in!
            val ndAlternation = """<LOD|LOD|<DL|DL|BDL|BLD|<RL|<LOQ|<\s*\d+(?:\.\d+)?\s*%?|N\.?\s*D\.?|N/D|N0|NO|N/?A|N\.A\.?|NONE|NIL|NULL|未检出|未检测|未测出|无检出|无显示|未见|空白|无|-{2,}|——|(?<=\s)-(?=\s|$)"""
            val inlineRegex = Regex(
                """(?<![A-Za-z0-9])($symbolAlternation)\s*[:=]?\s*(?:($ndAlternation)(?![A-Za-z0-9.])|(\d+(?:\.\d+)?)\s*(%|ppm)?)""",
                RegexOption.IGNORE_CASE
            )
            inlineRegex.findAll(aliasCorrectedText).forEach { match ->
                val rawSym = match.groupValues[1].uppercase(Locale.US)
                val lodToken = match.groupValues[2]
                val numStr = match.groupValues[3]
                val unit = match.groupValues[4].lowercase(Locale.US)

                if (lodToken.isNotBlank()) {
                    if (rawSym != "LE") {
                        val canonical = canonicalByUpper[rawSym] ?: return@forEach
                        if (!elements.containsKey(canonical)) {
                            ndElements.add(canonical)
                        }
                    }
                    return@forEach
                }
                val parsed = numStr.toDoubleOrNull() ?: return@forEach
                val pct = if (unit == "ppm") parsed / 10_000.0 else parsed
                if (pct in 0.0..100.0) {
                    if (rawSym == "LE") {
                        if (lightElementPct == null) lightElementPct = pct
                    } else {
                        val canonical = canonicalByUpper[rawSym] ?: return@forEach
                        if (!elements.containsKey(canonical)) {
                            elements[canonical] = pct
                            ndElements.remove(canonical)
                        }
                    }
                }
            }

            // Pattern B: vertical pairing where symbol is alone on line i and value is alone on line i+1
            if (allowVerticalPairFallback) {
                val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                for (i in 0 until lines.lastIndex) {
                    val sym = resolveStandaloneElementOrLe(lines[i], canonicalByUpper) ?: continue
                    val valPair = parseStandaloneValueToken(lines[i + 1]) ?: continue
                    if (valPair.second) {
                        if (sym != "LE" && !elements.containsKey(sym)) {
                            ndElements.add(sym)
                        }
                    } else if (valPair.first != null && valPair.first!! in 0.0..100.0) {
                        if (sym == "LE") {
                            if (lightElementPct == null) lightElementPct = valPair.first
                        } else if (!elements.containsKey(sym)) {
                            elements[sym] = valPair.first!!
                            ndElements.remove(sym)
                        }
                    }
                }
            }
        }

        val detectedGrade = extractBestGrade(cleanText.lines(), cleanText)
        val isNonMetal = nonMetalKeywords.any { cleanText.contains(it, ignoreCase = true) } &&
            elements.isEmpty() && ndElements.isEmpty()
        val materialType = if (isNonMetal) "非金属" else "金属"

        return SpectrometerOcrResult(
            materialType = materialType,
            detectedGrade = detectedGrade,
            elements = elements,
            rawOcrSummary = buildSummary(elements, ndElements, detectedGrade, isNonMetal, lightElementPct),
            detectedLightElementPct = lightElementPct,
            ndElements = ndElements
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

    fun isNdOrNoneToken(token: String): Boolean {
        val t = token.trim()
        if (t.isEmpty()) return false
        val upper = t.uppercase(Locale.US)
        return upper in setOf(
            "ND", "N.D.", "N.D", "N D", "N/D", "N0", "NO",
            "<LOD", "LOD", "<DL", "DL", "BDL", "BLD", "<RL", "<LOQ",
            "N/A", "NA", "N.A.", "N.A", "NONE", "NIL", "NULL",
            "-", "--", "---", "——", "/",
            "无", "未检出", "未检测", "未测出", "无检出", "无显示", "未见", "空白", "零"
        ) || t.startsWith("<")
    }

    private fun parseStandaloneValueToken(token: String): Pair<Double?, Boolean>? {
        val t = token.trim()
        if (isNdOrNoneToken(t)) {
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
        ndElements: Set<String>,
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
            } else if (ndElements.isEmpty()) {
                add("离线 OCR 未匹配到标准元素百分比")
            }
            if (ndElements.isNotEmpty()) {
                add("未检出(ND)：${ndElements.joinToString("、")}")
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

                // Stage 2: Always try LCD screen ROI crop + upscale + contrast boost unless >= 5 elements already extracted
                if (bestAttempt.result.elements.size < 5) {
                    val lcdBitmap = withContext(Dispatchers.Default) {
                        extractEnhancedLcdRegion(baseBitmap)
                    }
                    if (lcdBitmap != null) {
                        try {
                            val lcdAttempt = runMultiOrientationSweep(
                                source = lcdBitmap,
                                targetElements = targetElements,
                                preferredRotation = bestAttempt.rotation
                            )
                            if (lcdAttempt.score > bestAttempt.score) {
                                bestAttempt = lcdAttempt
                            }

                            // Stage 3: High-contrast grayscale pass on cropped LCD to remove RGB moire fringes
                            if (bestAttempt.result.elements.size < 4) {
                                val grayLcd = withContext(Dispatchers.Default) {
                                    toHighContrastGrayscale(lcdBitmap)
                                }
                                try {
                                    val grayAttempt = runSingleOrientationOcr(
                                        source = grayLcd,
                                        degrees = bestAttempt.rotation,
                                        targetElements = targetElements
                                    )
                                    if (grayAttempt.score > bestAttempt.score) {
                                        bestAttempt = grayAttempt
                                    }
                                } finally {
                                    if (!grayLcd.isRecycled) grayLcd.recycle()
                                }
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
        targetElements: List<String>,
        preferredRotation: Int = 0
    ): OcrSweepAttempt {
        val baseOrder = listOf(0, 90, 270, 180)
        val rotations = if (preferredRotation in baseOrder) {
            listOf(preferredRotation) + baseOrder.filter { it != preferredRotation }
        } else {
            baseOrder
        }
        var best: OcrSweepAttempt? = null

        for (deg in rotations) {
            val candidate = runSingleOrientationOcr(source, deg, targetElements)
            if (best == null || candidate.score > best.score) {
                best = candidate
            }

            // Short-circuit only when a clear multi-element table (>= 4 elements) with grade/LE is extracted
            if (candidate.result.elements.size >= 4 &&
                (candidate.result.detectedGrade.isNotBlank() || candidate.result.detectedLightElementPct != null)
            ) {
                break
            }
        }

        return best ?: OcrSweepAttempt(
            result = SpectrometerTextParser.parsePlainText("", targetElements),
            score = 0,
            rotation = 0,
            rawSnippet = ""
        )
    }

    private suspend fun runSingleOrientationOcr(
        source: Bitmap,
        degrees: Int,
        targetElements: List<String>
    ): OcrSweepAttempt {
        val rotated = if (degrees == 0) source else rotateBitmap(source, degrees.toFloat())
        return try {
            val inputImage = InputImage.fromBitmap(rotated, 0)
            val visionText = recognizer.process(inputImage).awaitResult()
            val spatialLines = extractCellLines(visionText)

            val parsed = if (spatialLines.isNotEmpty()) {
                SpectrometerTextParser.parseFromSpatialLines(spatialLines, targetElements)
            } else {
                SpectrometerTextParser.parsePlainText(visionText.text, targetElements)
            }

            val hasTableCues = visionText.text.contains("LE", ignoreCase = true) ||
                visionText.text.contains("ND", ignoreCase = true) ||
                visionText.text.contains("无") ||
                visionText.text.contains("%")
            val score = parsed.elements.size * 10 +
                parsed.ndElements.size * 5 +
                (if (parsed.detectedGrade.isNotBlank()) 4 else 0) +
                (if (parsed.detectedLightElementPct != null) 4 else 0) +
                (if (hasTableCues) 3 else 0) +
                spatialLines.size.coerceAtMost(5)

            OcrSweepAttempt(
                result = parsed,
                score = score,
                rotation = degrees,
                rawSnippet = visionText.text.replace("\n", " / ")
            )
        } finally {
            if (degrees != 0 && rotated !== source && !rotated.isRecycled) {
                rotated.recycle()
            }
        }
    }

    /**
     * Splits multi-column ML Kit Text.Lines into individual cell boxes at wide horizontal column gaps,
     * while preserving tight tokens (like "0.198", "<0.14", "+/- 3sigma") inside one cell.
     */
    private fun extractCellLines(visionText: com.google.mlkit.vision.text.Text): List<OcrSpatialLine> {
        val cells = mutableListOf<OcrSpatialLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val elems = line.elements
                val lineHeight = line.boundingBox?.height()?.coerceAtLeast(12) ?: 24
                if (elems.size <= 1) {
                    val box = line.boundingBox
                    if (box != null) {
                        cells += OcrSpatialLine(line.text, box.left, box.top, box.right, box.bottom)
                    } else {
                        cells += OcrSpatialLine(line.text, 0, cells.size * 30, 100, cells.size * 30 + 24)
                    }
                    continue
                }

                val splitGap = max(6, (lineHeight * 0.55f).toInt())
                var curText = StringBuilder(elems[0].text)
                var curBox = elems[0].boundingBox
                for (i in 1 until elems.size) {
                    val next = elems[i]
                    val nextBox = next.boundingBox
                    val gap = if (curBox != null && nextBox != null) nextBox.left - curBox.right else 0
                    val prevStr = curText.toString().trim()
                    val nextStr = next.text.trim()
                    val keepTogether = prevStr.endsWith(".") || nextStr.startsWith(".") ||
                        prevStr == "<" || prevStr == "+/-" ||
                        (prevStr == "%" && nextStr.equals("v", ignoreCase = true))

                    if (gap <= splitGap || keepTogether) {
                        if (curBox != null && nextBox != null) {
                            curBox = android.graphics.Rect(
                                min(curBox.left, nextBox.left),
                                min(curBox.top, nextBox.top),
                                max(curBox.right, nextBox.right),
                                max(curBox.bottom, nextBox.bottom)
                            )
                        }
                        if (prevStr.endsWith(".") || nextStr.startsWith(".") || prevStr == "<") {
                            curText.append(next.text)
                        } else {
                            curText.append(' ').append(next.text)
                        }
                    } else {
                        if (curBox != null) {
                            cells += OcrSpatialLine(curText.toString(), curBox.left, curBox.top, curBox.right, curBox.bottom)
                        }
                        curText = StringBuilder(next.text)
                        curBox = nextBox
                    }
                }
                if (curBox != null) {
                    cells += OcrSpatialLine(curText.toString(), curBox.left, curBox.top, curBox.right, curBox.bottom)
                }
            }
        }
        return cells
    }

    /**
     * Locates the bright white/gray spectrometer LCD screen inside a photo, crops it with 4-side padding
     * (so the blue header bar is kept regardless of phone orientation), upscales if needed, and boosts contrast.
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

        // Pad all 4 sides generously (16%) so the blue Alloy/Grade header bar stays inside regardless of camera orientation
        val padX = (compW * 0.16f).toInt().coerceAtLeast(4)
        val padY = (compH * 0.16f).toInt().coerceAtLeast(4)

        val cropLeft = (((bestMinX - padX).coerceAtLeast(0).toFloat() / gridW) * w).toInt()
        val cropTop = (((bestMinY - padY).coerceAtLeast(0).toFloat() / gridH) * h).toInt()
        val cropRight = (((bestMaxX + padX).coerceAtMost(gridW - 1).toFloat() / gridW) * w).toInt()
        val cropBottom = (((bestMaxY + padY).coerceAtMost(gridH - 1).toFloat() / gridH) * h).toInt()

        val cropW = (cropRight - cropLeft).coerceAtLeast(1)
        val cropH = (cropBottom - cropTop).coerceAtLeast(1)
        if (cropW < 100 || cropH < 100) return null

        val cropped = Bitmap.createBitmap(source, cropLeft, cropTop, cropW, cropH)
        val maxSide = max(cropW, cropH)
        val targetBitmap = if (maxSide < 1500) {
            val scale = 1500f / maxSide.toFloat()
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

    private fun toHighContrastGrayscale(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.35f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        gray.postConcat(contrastMatrix)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(gray)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
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
