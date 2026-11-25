package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.InspectionRepository
import com.example.data.SpectrometerOcrRepository
import com.example.data.SpectrometerOcrResult
import com.example.model.ComponentInspection
import com.example.model.DefaultFieldCandidates
import com.example.model.EquipmentRecord
import com.example.model.EquipmentTemplateType
import com.example.model.FieldCategory
import com.example.model.InspectionReportMeta
import com.example.model.PartStatus
import com.example.model.SectionType
import com.example.model.TemplateSchemaConfig
import com.example.model.UiState
import com.example.util.PhotoStorageHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data class Analyzing(val savedPhotoPath: String = "") : OcrUiState
    data class Recognized(val result: SpectrometerOcrResult, val savedPhotoPath: String) : OcrUiState
    data class Error(val message: String, val savedPhotoPath: String = "") : OcrUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class InspectionViewModel(
    private val repository: InspectionRepository,
    private val ocrRepository: SpectrometerOcrRepository
) : ViewModel() {

    val metaState: StateFlow<UiState<InspectionReportMeta>> = repository.observeMeta()
        .map<InspectionReportMeta, UiState<InspectionReportMeta>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(error.message ?: "无法加载本地表头信息")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    val templateConfigState: StateFlow<TemplateSchemaConfig> = repository.observeTemplateConfig()
        .catch { emit(TemplateSchemaConfig()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = TemplateSchemaConfig()
        )

    val candidatesByFieldState: StateFlow<Map<String, List<String>>> = repository.observeCandidatesByField()
        .catch { emit(DefaultFieldCandidates.builtInByFieldKey) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DefaultFieldCandidates.builtInByFieldKey
        )

    val equipmentsState: StateFlow<UiState<List<EquipmentRecord>>> = repository.observeEquipments()
        .map<List<EquipmentRecord>, UiState<List<EquipmentRecord>>> { UiState.Success(it) }
        .catch { error -> emit(UiState.Error(error.message ?: "无法加载本地设备列表")) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private val _selectedEquipmentId = MutableStateFlow<String?>(null)
    val selectedEquipmentId: StateFlow<String?> = _selectedEquipmentId.asStateFlow()

    val partsState: StateFlow<UiState<List<ComponentInspection>>> = _selectedEquipmentId
        .flatMapLatest { eqId ->
            if (eqId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeParts(eqId)
                    .map<List<ComponentInspection>, UiState<List<ComponentInspection>>> { UiState.Success(it) }
                    .catch { error -> emit(UiState.Error(error.message ?: "无法加载零部件清单")) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading
        )

    private val _ocrState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val ocrState: StateFlow<OcrUiState> = _ocrState.asStateFlow()

    private val _reportDataState = MutableStateFlow<UiState<List<Pair<EquipmentRecord, List<ComponentInspection>>>>>(UiState.Loading)
    val reportDataState: StateFlow<UiState<List<Pair<EquipmentRecord, List<ComponentInspection>>>>> = _reportDataState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    fun selectEquipment(equipmentId: String?) {
        _selectedEquipmentId.value = equipmentId
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun resetOcrState() {
        _ocrState.value = OcrUiState.Idle
    }

    fun showNotice(message: String) {
        _statusMessage.value = message
    }

    fun saveMeta(meta: InspectionReportMeta, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.saveMeta(meta)
            _isSaving.value = false
            result.onSuccess {
                _statusMessage.value = "表头工序与文件信息已保存到本地"
                onComplete()
            }.onFailure { e ->
                _statusMessage.value = "保存失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun toggleTemplateField(fieldKey: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.toggleTemplateField(fieldKey, enabled)
        }
    }

    fun addCustomTemplateField(
        label: String,
        category: FieldCategory,
        enableWaterfallCandidates: Boolean,
        onAdded: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.addCustomTemplateField(label, category, enableWaterfallCandidates)
            res.onSuccess {
                _statusMessage.value = "已添加模板字段：$label"
                onAdded()
            }.onFailure { e ->
                _statusMessage.value = e.localizedMessage ?: "添加字段失败"
            }
        }
    }

    fun removeCustomTemplateField(fieldKey: String) {
        viewModelScope.launch {
            repository.removeCustomTemplateField(fieldKey)
                .onSuccess { _statusMessage.value = "已移除自定义字段" }
        }
    }

    fun resetTemplateConfig() {
        viewModelScope.launch {
            repository.resetTemplateConfigToDefault()
                .onSuccess { _statusMessage.value = "已恢复默认模板字段" }
        }
    }

    fun deleteCandidate(fieldKey: String, value: String) {
        viewModelScope.launch {
            repository.deleteCandidate(fieldKey, value)
        }
    }

    fun createEquipment(
        sequenceNo: Long,
        tagNumber: String,
        equipmentName: String,
        specModel: String,
        systemName: String,
        sectionType: SectionType,
        workshopLocation: String,
        infraType: String,
        templateType: EquipmentTemplateType,
        customFields: Map<String, String> = emptyMap(),
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.createEquipmentWithTemplate(
                sequenceNo = sequenceNo,
                tagNumber = tagNumber,
                equipmentName = equipmentName,
                specModel = specModel,
                systemName = systemName,
                sectionType = sectionType,
                workshopLocation = workshopLocation,
                infraType = infraType,
                templateType = templateType,
                customFields = customFields
            )
            _isSaving.value = false
            result.onSuccess { newId ->
                _selectedEquipmentId.value = newId
                _statusMessage.value = "已登记 $tagNumber（纯离线保存）"
                onCreated(newId)
            }.onFailure { e ->
                _statusMessage.value = "登记失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun duplicateEquipment(
        source: EquipmentRecord,
        newSequenceNo: Long,
        newTagNumber: String,
        newSpecModel: String,
        onDuplicated: (String) -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.duplicateEquipment(source, newSequenceNo, newTagNumber, newSpecModel)
            _isSaving.value = false
            result.onSuccess { newId ->
                _selectedEquipmentId.value = newId
                _statusMessage.value = "已复制模板并创建设备 $newTagNumber"
                onDuplicated(newId)
            }.onFailure { e ->
                _statusMessage.value = "复制失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun deleteEquipment(equipmentId: String) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.deleteEquipment(equipmentId)
            _isSaving.value = false
            result.onSuccess {
                if (_selectedEquipmentId.value == equipmentId) {
                    _selectedEquipmentId.value = null
                }
                _statusMessage.value = "设备记录与留存照片已删除"
            }.onFailure { e ->
                _statusMessage.value = "删除失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun addCustomPart(
        equipmentId: String,
        codeNumber: String,
        componentGroup: String,
        partName: String,
        quantity: String,
        systemName: String,
        currentTotalParts: Long,
        nextSortOrder: Long,
        onAdded: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val result = repository.addCustomPart(
                equipmentId = equipmentId,
                codeNumber = codeNumber,
                componentGroup = componentGroup,
                partName = partName,
                quantity = quantity,
                systemName = systemName,
                currentTotalParts = currentTotalParts,
                nextSortOrder = nextSortOrder
            )
            _isSaving.value = false
            result.onSuccess { newPartId ->
                _statusMessage.value = "已添加部位：$partName"
                onAdded(newPartId)
            }.onFailure { e ->
                _statusMessage.value = "添加部位失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun deletePart(
        equipmentId: String,
        part: ComponentInspection,
        allParts: List<ComponentInspection>
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val res = repository.deletePart(equipmentId, part, allParts)
            _isSaving.value = false
            res.onSuccess {
                _statusMessage.value = "已删除部位：${part.partName}"
            }.onFailure { e ->
                _statusMessage.value = "删除失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun persistAndAnalyzeSpectrometerPhoto(
        context: Context,
        imageUri: Uri,
        equipmentId: String,
        partId: String,
        partName: String,
        previousPhotoPath: String
    ) {
        viewModelScope.launch {
            _ocrState.value = OcrUiState.Analyzing(previousPhotoPath)
            val persistRes = PhotoStorageHelper.persistInspectionPhoto(
                context = context,
                sourceUri = imageUri,
                equipmentId = equipmentId,
                partId = partId,
                previousPhotoPath = previousPhotoPath
            )
            val savedPath = persistRes.getOrNull().orEmpty()
            if (savedPath.isBlank()) {
                val errMsg = persistRes.exceptionOrNull()?.localizedMessage ?: "照片保存到本地失败，请重新拍摄或选图"
                _statusMessage.value = errMsg
                _ocrState.value = OcrUiState.Error(message = errMsg, savedPhotoPath = previousPhotoPath)
                return@launch
            }

            // Persist photoPath into Room immediately so exiting or switching parts never loses the photo
            repository.savePartPhoto(partId, savedPath)
            _ocrState.value = OcrUiState.Analyzing(savedPath)

            val targetElements = templateConfigState.value.activeElementSymbols()
            val ocrRes = ocrRepository.recognizeSpectrometerPhoto(
                context = context,
                imageSource = imageUri,
                localFilePath = savedPath,
                partName = partName,
                targetElements = targetElements
            )
            ocrRes.onSuccess { ocr ->
                _ocrState.value = OcrUiState.Recognized(ocr, savedPath)
            }.onFailure { e ->
                _ocrState.value = OcrUiState.Error(
                    message = e.localizedMessage ?: "本地 OCR 识别失败，请重拍或手动录入",
                    savedPhotoPath = savedPath
                )
            }
        }
    }

    fun savePartInspection(
        equipmentId: String,
        updatedPart: ComponentInspection,
        allParts: List<ComponentInspection>,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val activeElements = templateConfigState.value.activeElementSymbols()
            val result = repository.savePartInspection(
                equipmentId = equipmentId,
                updatedPart = updatedPart,
                allPartsForEquipment = allParts,
                allowedElementSymbols = activeElements
            )
            _isSaving.value = false
            result.onSuccess {
                _statusMessage.value = "已本地保存 ${updatedPart.partName} 材质与照片"
                onSaved()
            }.onFailure { e ->
                _statusMessage.value = "保存失败：${e.localizedMessage ?: "请重试"}"
            }
        }
    }

    fun loadStructuredReport() {
        viewModelScope.launch {
            _reportDataState.value = UiState.Loading
            val result = repository.fetchAllEquipmentsWithParts()
            result.onSuccess { list ->
                _reportDataState.value = UiState.Success(list)
            }.onFailure { e ->
                _reportDataState.value = UiState.Error(e.localizedMessage ?: "加载报表数据失败")
            }
        }
    }

    fun findNextPartId(parts: List<ComponentInspection>, currentPartId: String): String? {
        val currentIdx = parts.indexOfFirst { it.id == currentPartId }
        if (currentIdx == -1) return parts.firstOrNull { it.resolvedStatus == PartStatus.PENDING }?.id
        val nextPendingAfter = parts.drop(currentIdx + 1)
            .firstOrNull { it.resolvedStatus == PartStatus.PENDING }
        if (nextPendingAfter != null) return nextPendingAfter.id

        val anyPending = parts.firstOrNull { it.id != currentPartId && it.resolvedStatus == PartStatus.PENDING }
        if (anyPending != null) return anyPending.id

        return parts.getOrNull(currentIdx + 1)?.id
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
