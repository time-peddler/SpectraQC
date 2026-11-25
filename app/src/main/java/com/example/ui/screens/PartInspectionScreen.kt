package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.SpectrometerTextParser
import com.example.model.ComponentInspection
import com.example.model.DefaultFieldCandidates
import com.example.model.EquipmentRecord
import com.example.model.PartStatus
import com.example.model.TemplateSchemaConfig
import com.example.ui.components.WaterfallAutocompleteField
import com.example.ui.viewmodel.OcrUiState
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PartInspectionScreen(
    equipment: EquipmentRecord?,
    part: ComponentInspection?,
    allParts: List<ComponentInspection>,
    templateConfig: TemplateSchemaConfig,
    candidatesByField: Map<String, List<String>>,
    ocrState: OcrUiState,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSelectPart: (ComponentInspection) -> Unit,
    onAnalyzeAndPersistPhoto: (
        imageUri: Uri,
        equipmentId: String,
        partId: String,
        partName: String,
        previousPhotoPath: String
    ) -> Unit,
    onResetOcr: () -> Unit,
    onDeleteCandidate: (fieldKey: String, value: String) -> Unit,
    onSaveAndStayOrBack: (ComponentInspection, Boolean) -> Unit
) {
    if (part == null || equipment == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("未找到该部位数据", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val context = LocalContext.current
    val activeElements = remember(templateConfig) { templateConfig.activeElementSymbols() }
    val customDetailFields = remember(templateConfig) { templateConfig.activeCustomPartDetailFields() }

    var status by remember(part.id) { mutableStateOf(part.resolvedStatus) }
    var materialType by remember(part.id) { mutableStateOf(part.materialType.ifBlank { "金属" }) }
    var detectedGrade by remember(part.id) { mutableStateOf(part.detectedGrade) }
    var ocrSummary by remember(part.id) { mutableStateOf(part.ocrSummary) }
    var savedPhotoPath by remember(part.id) { mutableStateOf(part.photoPath) }
    var capturedImageUri by remember(part.id) { mutableStateOf<Uri?>(null) }
    var pendingCameraUriString by rememberSaveable(part.id) { mutableStateOf<String?>(null) }
    var permissionNotice by remember(part.id) { mutableStateOf<String?>(null) }
    var showPhotoPreviewDialog by remember(part.id) { mutableStateOf(false) }

    var showRiskSection by remember(part.id) { mutableStateOf(customDetailFields.isNotEmpty()) }
    var severityS by remember(part.id) { mutableStateOf(part.severityS) }
    var motionState by remember(part.id) { mutableStateOf(part.motionState) }
    var motionEval by remember(part.id) { mutableStateOf(part.motionEval) }
    var locationPos by remember(part.id) { mutableStateOf(part.locationPos) }
    var frequencyO by remember(part.id) { mutableStateOf(part.frequencyO) }
    var detectionMethod by remember(part.id) { mutableStateOf(part.detectionMethod.ifBlank { "便携式光谱仪" }) }
    var detectionD by remember(part.id) { mutableStateOf(part.detectionD) }
    var failureConsequence by remember(part.id) { mutableStateOf(part.failureConsequence) }
    var riskLevel by remember(part.id) { mutableStateOf(part.riskLevel) }
    var controlMeasure by remember(part.id) { mutableStateOf(part.controlMeasure) }
    var activityDescription by remember(part.id) { mutableStateOf(part.activityDescription) }
    var inspectorNotes by remember(part.id) { mutableStateOf(part.inspectorNotes) }

    val customFieldValues = remember(part.id) {
        mutableStateMapOf<String, String>().apply {
            putAll(part.customFields)
        }
    }

    val elementTextMap = remember(part.id, activeElements) {
        mutableStateMapOf<String, String>().apply {
            for (symbol in activeElements) {
                val existing = part.elements[symbol]
                this[symbol] = when {
                    existing != null -> formatElementValue(existing)
                    symbol in part.ndElements -> "ND"
                    else -> ""
                }
            }
        }
    }

    LaunchedEffect(part.id) {
        onResetOcr()
    }

    // Keep photo path synchronized with Room updates even across immediate back navigation
    LaunchedEffect(part.photoPath) {
        if (part.photoPath.isNotBlank() && savedPhotoPath.isBlank()) {
            savedPhotoPath = part.photoPath
        }
    }

    // Apply offline OCR results & persisted photo path
    LaunchedEffect(ocrState, part.id) {
        when (ocrState) {
            is OcrUiState.Analyzing -> {
                if (ocrState.savedPhotoPath.isNotBlank()) {
                    savedPhotoPath = ocrState.savedPhotoPath
                }
            }
            is OcrUiState.Recognized -> {
                val res = ocrState.result
                if (ocrState.savedPhotoPath.isNotBlank()) {
                    savedPhotoPath = ocrState.savedPhotoPath
                }
                materialType = res.materialType
                status = if (res.materialType == "非金属") PartStatus.NON_METAL else PartStatus.VERIFIED
                if (res.detectedGrade.isNotBlank()) {
                    detectedGrade = res.detectedGrade
                }
                ocrSummary = res.rawOcrSummary
                if (res.elements.isNotEmpty() || res.ndElements.isNotEmpty()) {
                    for (symbol in activeElements) {
                        val v = res.elements[symbol]
                        elementTextMap[symbol] = when {
                            v != null -> formatElementValue(v)
                            symbol in res.ndElements -> "ND"
                            else -> ""
                        }
                    }
                }
            }
            is OcrUiState.Error -> {
                if (ocrState.savedPhotoPath.isNotBlank()) {
                    savedPhotoPath = ocrState.savedPhotoPath
                }
            }
            else -> Unit
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUriString?.let { Uri.parse(it) }
        pendingCameraUriString = null
        if (success && uri != null) {
            capturedImageUri = uri
            permissionNotice = null
            onAnalyzeAndPersistPhoto(uri, equipment.id, part.id, part.partName, savedPhotoPath)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempPhotoUri(context)
            pendingCameraUriString = uri.toString()
            takePictureLauncher.launch(uri)
        } else {
            permissionNotice = "未授予相机权限，您仍可通过“相册选图”导入光谱仪照片或手动录入。"
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            capturedImageUri = uri
            permissionNotice = null
            onAnalyzeAndPersistPhoto(uri, equipment.id, part.id, part.partName, savedPhotoPath)
        }
    }

    val currentIndex = allParts.indexOfFirst { it.id == part.id }
    val prevPart = if (currentIndex > 0) allParts[currentIndex - 1] else null
    val nextPart = if (currentIndex >= 0 && currentIndex + 1 < allParts.size) allParts[currentIndex + 1] else null

    val cuVal = elementTextMap["Cu"]?.toDoubleOrNull() ?: 0.0
    val znVal = elementTextMap["Zn"]?.toDoubleOrNull() ?: 0.0
    val cuZnTotal = cuVal + znVal
    val cuZnDisplay = if (status == PartStatus.NON_METAL || materialType == "非金属") {
        "0%"
    } else if (cuZnTotal <= 0.0001) {
        "0%"
    } else if (kotlin.math.round(cuZnTotal * 1000).toLong() % 10L != 0L) {
        String.format(Locale.US, "%.3f%%", cuZnTotal)
    } else {
        String.format(Locale.US, "%.2f%%", cuZnTotal)
    }

    val activePhotoModel: Any? = remember(savedPhotoPath, capturedImageUri) {
        val file = savedPhotoPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (file != null && file.exists()) file else capturedImageUri
    }

    fun buildUpdatedPart(targetStatus: PartStatus): ComponentInspection {
        val parsedElements = part.elements.toMutableMap()
        val parsedNdElements = part.ndElements.toMutableSet()
        if (targetStatus == PartStatus.NON_METAL || materialType == "非金属") {
            parsedElements.clear()
            parsedNdElements.clear()
        } else {
            for (symbol in activeElements) {
                val raw = elementTextMap[symbol]?.trim().orEmpty()
                val num = raw.toDoubleOrNull()
                if (num != null && num >= 0.0) {
                    parsedElements[symbol] = num.coerceIn(0.0, 100.0)
                    parsedNdElements.remove(symbol)
                } else if (SpectrometerTextParser.isNdOrNoneToken(raw)) {
                    parsedElements.remove(symbol)
                    parsedNdElements.add(symbol)
                } else {
                    parsedElements.remove(symbol)
                    parsedNdElements.remove(symbol)
                }
            }
        }
        return part.copy(
            status = targetStatus.name,
            materialType = if (targetStatus == PartStatus.NON_METAL) "非金属" else materialType,
            detectedGrade = detectedGrade.trim(),
            elements = parsedElements,
            ndElements = parsedNdElements,
            cuZnImpurity = cuZnDisplay,
            severityS = severityS,
            motionState = motionState,
            motionEval = motionEval,
            locationPos = locationPos,
            frequencyO = frequencyO,
            detectionMethod = detectionMethod,
            detectionD = detectionD,
            failureConsequence = failureConsequence,
            riskLevel = riskLevel,
            controlMeasure = controlMeasure,
            activityDescription = activityDescription,
            ocrSummary = ocrSummary,
            inspectorNotes = inspectorNotes,
            photoPath = savedPhotoPath.ifBlank { part.photoPath },
            customFields = customFieldValues.toMap()
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${part.codeNumber} ${part.partName} (×${part.quantity})",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${equipment.tagNumber} · ${part.componentGroup} (${currentIndex + 1}/${allParts.size})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("inspection_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回部位列表")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { prevPart?.let(onSelectPart) },
                        enabled = prevPart != null
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "上一部位")
                    }
                    IconButton(
                        onClick = { nextPart?.let(onSelectPart) },
                        enabled = nextPart != null
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "下一部位")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val resolved = if (status == PartStatus.PENDING) PartStatus.VERIFIED else status
                                onSaveAndStayOrBack(buildUpdatedPart(resolved), false)
                            },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.42f)
                                .height(48.dp)
                                .testTag("save_and_back_button")
                        ) {
                            Text("保存返回")
                        }
                        Button(
                            onClick = {
                                val resolved = if (status == PartStatus.PENDING) PartStatus.VERIFIED else status
                                onSaveAndStayOrBack(buildUpdatedPart(resolved), true)
                            },
                            enabled = !isSaving,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(0.58f)
                                .height(48.dp)
                                .testTag("save_and_next_button")
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("确认保存并测下一项")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allParts.forEach { item ->
                    val isCurrent = item.id == part.id
                    FilterChip(
                        selected = isCurrent,
                        onClick = { if (!isCurrent) onSelectPart(item) },
                        label = {
                            val marker = when (item.resolvedStatus) {
                                PartStatus.VERIFIED -> "✓ "
                                PartStatus.NON_METAL -> "○ "
                                PartStatus.PENDING -> ""
                            }
                            Text("$marker${item.partName}")
                        }
                    )
                }
            }

            // 1. Offline Spectrometer Photo Capture, Persistent Retention & Local OCR Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "离线光谱仪拍照 OCR 与照片留存",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "本地离线识别（不联网），原图自动留存并在导出 XLSX 时嵌入表格",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CAMERA
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    val uri = createTempPhotoUri(context)
                                    pendingCameraUriString = uri.toString()
                                    takePictureLauncher.launch(uri)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("capture_spectrometer_button")
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("拍照识别")
                        }

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("pick_spectrometer_photo_button")
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("相册选图")
                        }
                    }

                    if (permissionNotice != null) {
                        Text(
                            text = permissionNotice.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    if (activePhotoModel != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showPhotoPreviewDialog = true }
                            ) {
                                AsyncImage(
                                    model = activePhotoModel,
                                    contentDescription = "已留存的光谱仪照片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomIn,
                                        contentDescription = "放大查看",
                                        modifier = Modifier
                                            .size(16.dp)
                                            .padding(2.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                when (ocrState) {
                                    is OcrUiState.Analyzing -> {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                            Text("正在离线 OCR 识别...", style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                    is OcrUiState.Recognized -> {
                                        val ndSuffix = if (ocrState.result.ndElements.isNotEmpty()) {
                                            " + ${ocrState.result.ndElements.size} 项 ND"
                                        } else {
                                            ""
                                        }
                                        Text(
                                            text = "识别完成 · ${ocrState.result.elements.size} 项数值$ndSuffix（照片已留存）",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text = ocrState.result.rawOcrSummary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    is OcrUiState.Error -> {
                                        Text(
                                            text = ocrState.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        TextButton(
                                            onClick = {
                                                val retryUri = capturedImageUri ?: Uri.fromFile(File(savedPhotoPath))
                                                onAnalyzeAndPersistPhoto(
                                                    retryUri,
                                                    equipment.id,
                                                    part.id,
                                                    part.partName,
                                                    savedPhotoPath
                                                )
                                            },
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("重新离线识别")
                                        }
                                    }
                                    is OcrUiState.Idle -> {
                                        Text(
                                            text = "照片已本地留存（点击左侧缩略图放大）",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (ocrSummary.isNotBlank()) {
                                            Text(
                                                text = ocrSummary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else if (ocrSummary.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "上次 OCR 摘要：$ocrSummary",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 2. Material Classification & CuZn Impurity Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "材质判定与状态确认",
                        style = MaterialTheme.typography.titleMedium
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = status != PartStatus.NON_METAL && materialType == "金属",
                            onClick = {
                                materialType = "金属"
                                status = PartStatus.VERIFIED
                            },
                            label = { Text("金属 (光谱实测)") }
                        )
                        FilterChip(
                            selected = status == PartStatus.NON_METAL || materialType == "非金属",
                            onClick = {
                                materialType = "非金属"
                                status = PartStatus.NON_METAL
                                if (detectedGrade.isBlank()) detectedGrade = "非金属"
                            },
                            label = { Text("非金属 (CuZn 0%)") }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_GRADE)) {
                            WaterfallAutocompleteField(
                                value = detectedGrade,
                                onValueChange = { detectedGrade = it },
                                label = templateConfig.labelFor(TemplateSchemaConfig.KEY_PART_GRADE, "识别牌号 / 材质"),
                                placeholder = "SS304 / 316L / WCB",
                                candidates = candidatesByField[TemplateSchemaConfig.KEY_PART_GRADE].orEmpty(),
                                onDeleteCandidate = { v -> onDeleteCandidate(TemplateSchemaConfig.KEY_PART_GRADE, v) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_CUZN)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    Text(
                                        text = "CuZn杂质",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = cuZnDisplay,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Dynamic Chemical Elements Grid driven by TemplateSchemaConfig
            if (status != PartStatus.NON_METAL && materialType != "非金属" && activeElements.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "组成成分 (%) · 请核对光谱数值",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "当前模板启用 ${activeElements.size} 项元素列（未检出可填 ND / 无 或留空）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    for (symbol in activeElements) {
                                        elementTextMap[symbol] = ""
                                    }
                                }
                            ) {
                                Text("清空")
                            }
                        }

                        activeElements.chunked(3).forEach { rowSymbols ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowSymbols.forEach { symbol ->
                                    val textVal = elementTextMap[symbol].orEmpty()
                                    val hasPositive = (textVal.toDoubleOrNull() ?: 0.0) > 0.0
                                    val isNd = SpectrometerTextParser.isNdOrNoneToken(textVal)
                                    OutlinedTextField(
                                        value = textVal,
                                        onValueChange = { raw ->
                                            val trimmed = raw.replace("\n", "").take(10)
                                            elementTextMap[symbol] = trimmed
                                            if (trimmed.isNotBlank() && status == PartStatus.PENDING) {
                                                status = PartStatus.VERIFIED
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = symbol,
                                                fontWeight = if (hasPositive || isNd) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    hasPositive -> MaterialTheme.colorScheme.primary
                                                    isNd -> MaterialTheme.colorScheme.secondary
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        },
                                        placeholder = { Text("ND/-") },
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.labelMedium,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("element_input_$symbol")
                                    )
                                }
                                repeat(3 - rowSymbols.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // 4. Collapsible Risk Assessment, Custom Maintained Fields & Activity Description
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRiskSection = !showRiskSection }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (customDetailFields.isNotEmpty()) {
                                    "自定义字段、风险评价与活动描述"
                                } else {
                                    "风险评价与活动描述 (选填)"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "按当前模板展示启用字段，点击展开填写",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (showRiskSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (showRiskSection) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // User-added custom part detail fields
                            customDetailFields.forEach { field ->
                                val valStr = customFieldValues[field.key].orEmpty()
                                val cands = (
                                    candidatesByField[field.key].orEmpty() +
                                        DefaultFieldCandidates.suggestionsForCustomLabel(field.label)
                                    ).distinct()
                                if (field.enableWaterfallCandidates) {
                                    WaterfallAutocompleteField(
                                        value = valStr,
                                        onValueChange = { customFieldValues[field.key] = it },
                                        label = field.label,
                                        candidates = cands,
                                        onDeleteCandidate = { v -> onDeleteCandidate(field.key, v) }
                                    )
                                } else {
                                    OutlinedTextField(
                                        value = valStr,
                                        onValueChange = { customFieldValues[field.key] = it },
                                        label = { Text(field.label) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            val showS = templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_SEVERITY_S)
                            val showO = templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_FREQUENCY_O)
                            val showD = templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_DETECTION_D)
                            if (showS || showO || showD) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (showS) {
                                        OutlinedTextField(
                                            value = severityS,
                                            onValueChange = { severityS = it },
                                            label = { Text("严重度 (S)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (showO) {
                                        OutlinedTextField(
                                            value = frequencyO,
                                            onValueChange = { frequencyO = it },
                                            label = { Text("频度 (O)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (showD) {
                                        OutlinedTextField(
                                            value = detectionD,
                                            onValueChange = { detectionD = it },
                                            label = { Text("探测度 (D)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_MOTION_STATE)) {
                                WaterfallAutocompleteField(
                                    value = motionState,
                                    onValueChange = { motionState = it },
                                    label = "运动状态",
                                    candidates = candidatesByField[TemplateSchemaConfig.KEY_PART_MOTION_STATE].orEmpty()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_MOTION_EVAL)) {
                                OutlinedTextField(
                                    value = motionEval,
                                    onValueChange = { motionEval = it },
                                    label = { Text("运动状态评价") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_LOCATION_POS)) {
                                WaterfallAutocompleteField(
                                    value = locationPos,
                                    onValueChange = { locationPos = it },
                                    label = "所处位置",
                                    candidates = candidatesByField[TemplateSchemaConfig.KEY_PART_LOCATION_POS].orEmpty()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_DETECTION_METHOD)) {
                                WaterfallAutocompleteField(
                                    value = detectionMethod,
                                    onValueChange = { detectionMethod = it },
                                    label = "探测方法",
                                    candidates = candidatesByField[TemplateSchemaConfig.KEY_PART_DETECTION_METHOD].orEmpty()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_RISK_LEVEL)) {
                                WaterfallAutocompleteField(
                                    value = riskLevel,
                                    onValueChange = { riskLevel = it },
                                    label = "风险等级",
                                    candidates = candidatesByField[TemplateSchemaConfig.KEY_PART_RISK_LEVEL].orEmpty()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_FAILURE_CONSEQUENCE)) {
                                OutlinedTextField(
                                    value = failureConsequence,
                                    onValueChange = { failureConsequence = it },
                                    label = { Text("失效后果") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (templateConfig.isFieldEnabled(TemplateSchemaConfig.KEY_PART_CONTROL_MEASURE)) {
                                OutlinedTextField(
                                    value = controlMeasure,
                                    onValueChange = { controlMeasure = it },
                                    label = { Text("消除/控制措施") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = activityDescription,
                                onValueChange = { activityDescription = it },
                                label = { Text("二、活动描述 & 功能") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = inspectorNotes,
                                onValueChange = { inspectorNotes = it },
                                label = { Text("质检员备注") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPhotoPreviewDialog && activePhotoModel != null) {
        Dialog(onDismissRequest = { showPhotoPreviewDialog = false }) {
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
                        Text(
                            text = "${part.partName} · 光谱留存原图",
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { showPhotoPreviewDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭预览")
                        }
                    }
                    AsyncImage(
                        model = activePhotoModel,
                        contentDescription = "留存照片预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 460.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
            }
        }
    }
}

private fun formatElementValue(value: Double): String {
    return if (value == 0.0) "0" else String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}

private fun createTempPhotoUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "spectra_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
