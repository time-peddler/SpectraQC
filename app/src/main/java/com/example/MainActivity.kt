package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.InspectionRepository
import com.example.data.SpectrometerOcrRepository
import com.example.data.local.SpectraDatabase
import com.example.model.UiState
import com.example.ui.screens.EquipmentPartsScreen
import com.example.ui.screens.HomeEquipmentListScreen
import com.example.ui.screens.PartInspectionScreen
import com.example.ui.screens.StructuredReportScreen
import com.example.ui.screens.TemplateMaintenanceScreen
import com.example.ui.theme.SpectraQcTheme
import com.example.ui.viewmodel.InspectionViewModel
import kotlinx.coroutines.launch

private const val ROUTE_EQUIPMENTS = "equipments"
private const val ROUTE_REPORT = "report"
private const val ROUTE_TEMPLATE_MAINTENANCE = "template_maintenance"
private const val ROUTE_EQUIPMENT_PARTS = "equipment_parts/{equipmentId}"
private const val ROUTE_PART_INSPECT = "part_inspect/{equipmentId}/{partId}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            SpectraQcTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpectraQcApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpectraQcApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: InspectionViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val db = SpectraDatabase.getInstance(app)
                InspectionViewModel(
                    repository = InspectionRepository(db.inspectionDao()),
                    ocrRepository = SpectrometerOcrRepository()
                )
            }
        }
    )

    val metaState by viewModel.metaState.collectAsStateWithLifecycle()
    val templateConfig by viewModel.templateConfigState.collectAsStateWithLifecycle()
    val candidatesByField by viewModel.candidatesByFieldState.collectAsStateWithLifecycle()
    val equipmentsState by viewModel.equipmentsState.collectAsStateWithLifecycle()
    val partsState by viewModel.partsState.collectAsStateWithLifecycle()
    val reportDataState by viewModel.reportDataState.collectAsStateWithLifecycle()
    val ocrState by viewModel.ocrState.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        val msg = statusMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val showTopLevelChrome = currentRoute == ROUTE_EQUIPMENTS || currentRoute == ROUTE_REPORT

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= Dp(600f)

        Row(modifier = Modifier.fillMaxSize()) {
            if (showTopLevelChrome && useNavRail) {
                NavigationRail {
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_EQUIPMENTS,
                        onClick = {
                            navController.navigate(ROUTE_EQUIPMENTS) {
                                launchSingleTop = true
                                popUpTo(ROUTE_EQUIPMENTS)
                            }
                        },
                        icon = { Icon(Icons.Default.PrecisionManufacturing, contentDescription = "设备台账") },
                        label = { Text("设备台账") }
                    )
                    NavigationRailItem(
                        selected = currentRoute == ROUTE_REPORT,
                        onClick = {
                            navController.navigate(ROUTE_REPORT) {
                                launchSingleTop = true
                                popUpTo(ROUTE_EQUIPMENTS)
                            }
                        },
                        icon = { Icon(Icons.Default.TableChart, contentDescription = "报表导出") },
                        label = { Text("报表导出") }
                    )
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    if (showTopLevelChrome) {
                        TopAppBar(
                            title = { Text("光谱质检 · SpectraQC") },
                            actions = {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.padding(end = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "纯离线采集",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        )
                    }
                },
                bottomBar = {
                    if (showTopLevelChrome && !useNavRail) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_EQUIPMENTS,
                                onClick = {
                                    navController.navigate(ROUTE_EQUIPMENTS) {
                                        launchSingleTop = true
                                        popUpTo(ROUTE_EQUIPMENTS)
                                    }
                                },
                                icon = { Icon(Icons.Default.PrecisionManufacturing, contentDescription = "设备台账") },
                                label = { Text("设备台账") }
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_REPORT,
                                onClick = {
                                    navController.navigate(ROUTE_REPORT) {
                                        launchSingleTop = true
                                        popUpTo(ROUTE_EQUIPMENTS)
                                    }
                                },
                                icon = { Icon(Icons.Default.TableChart, contentDescription = "报表导出") },
                                label = { Text("报表导出") }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_EQUIPMENTS
                    ) {
                        composable(ROUTE_EQUIPMENTS) {
                            HomeEquipmentListScreen(
                                metaState = metaState,
                                equipmentsState = equipmentsState,
                                templateConfig = templateConfig,
                                candidatesByField = candidatesByField,
                                isSaving = isSaving,
                                onOpenEquipment = { eq ->
                                    viewModel.selectEquipment(eq.id)
                                    navController.navigate("equipment_parts/${eq.id}")
                                },
                                onOpenTemplateMaintenance = {
                                    navController.navigate(ROUTE_TEMPLATE_MAINTENANCE)
                                },
                                onSaveMeta = { meta, onComplete -> viewModel.saveMeta(meta, onComplete) },
                                onDeleteCandidate = { fieldKey, value -> viewModel.deleteCandidate(fieldKey, value) },
                                onCreateEquipment = { seq, tag, name, spec, sys, sec, loc, infra, tpl, custom, onDone ->
                                    viewModel.createEquipment(
                                        seq, tag, name, spec, sys, sec, loc, infra, tpl, custom
                                    ) { newEqId ->
                                        onDone(newEqId)
                                        navController.navigate("equipment_parts/$newEqId")
                                    }
                                },
                                onDuplicateEquipment = { src, seq, newTag, newSpec, onDone ->
                                    viewModel.duplicateEquipment(src, seq, newTag, newSpec) { newEqId ->
                                        onDone(newEqId)
                                        navController.navigate("equipment_parts/$newEqId")
                                    }
                                },
                                onDeleteEquipment = { eqId -> viewModel.deleteEquipment(eqId) }
                            )
                        }

                        composable(ROUTE_REPORT) {
                            StructuredReportScreen(
                                metaState = metaState,
                                reportDataState = reportDataState,
                                templateConfig = templateConfig,
                                onRefresh = { viewModel.loadStructuredReport() },
                                onOpenTemplateMaintenance = {
                                    navController.navigate(ROUTE_TEMPLATE_MAINTENANCE)
                                },
                                onExportNotice = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            )
                        }

                        composable(ROUTE_TEMPLATE_MAINTENANCE) {
                            TemplateMaintenanceScreen(
                                templateConfig = templateConfig,
                                onBack = { navController.popBackStack() },
                                onToggleField = { key, enabled -> viewModel.toggleTemplateField(key, enabled) },
                                onAddField = { label, cat, waterfall, onDone ->
                                    viewModel.addCustomTemplateField(label, cat, waterfall, onDone)
                                },
                                onRemoveCustomField = { key -> viewModel.removeCustomTemplateField(key) },
                                onResetDefault = { viewModel.resetTemplateConfig() }
                            )
                        }

                        composable(
                            route = ROUTE_EQUIPMENT_PARTS,
                            arguments = listOf(navArgument("equipmentId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val equipmentId = backStackEntry.arguments?.getString("equipmentId").orEmpty()
                            LaunchedEffect(equipmentId) {
                                viewModel.selectEquipment(equipmentId)
                            }
                            val equipments = (equipmentsState as? UiState.Success)?.data.orEmpty()
                            val activeEq = equipments.firstOrNull { it.id == equipmentId }

                            EquipmentPartsScreen(
                                equipment = activeEq,
                                partsState = partsState,
                                templateConfig = templateConfig,
                                candidatesByField = candidatesByField,
                                isSaving = isSaving,
                                onBack = { navController.popBackStack() },
                                onOpenPart = { part ->
                                    navController.navigate("part_inspect/$equipmentId/${part.id}")
                                },
                                onAddCustomPart = { code, group, name, qty, sys ->
                                    val currentParts = (partsState as? UiState.Success)?.data.orEmpty()
                                    val nextSort = (currentParts.maxOfOrNull { it.sortOrder } ?: 0L) + 1L
                                    viewModel.addCustomPart(
                                        equipmentId = equipmentId,
                                        codeNumber = code,
                                        componentGroup = group,
                                        partName = name,
                                        quantity = qty,
                                        systemName = sys,
                                        currentTotalParts = currentParts.size.toLong(),
                                        nextSortOrder = nextSort
                                    )
                                },
                                onDeletePart = { part, currentParts ->
                                    viewModel.deletePart(equipmentId, part, currentParts)
                                }
                            )
                        }

                        composable(
                            route = ROUTE_PART_INSPECT,
                            arguments = listOf(
                                navArgument("equipmentId") { type = NavType.StringType },
                                navArgument("partId") { type = NavType.StringType }
                            )
                        ) { backStackEntry ->
                            val equipmentId = backStackEntry.arguments?.getString("equipmentId").orEmpty()
                            val partId = backStackEntry.arguments?.getString("partId").orEmpty()
                            LaunchedEffect(equipmentId) {
                                viewModel.selectEquipment(equipmentId)
                            }
                            val equipments = (equipmentsState as? UiState.Success)?.data.orEmpty()
                            val activeEq = equipments.firstOrNull { it.id == equipmentId }
                            val allParts = (partsState as? UiState.Success)?.data.orEmpty()
                            val activePart = allParts.firstOrNull { it.id == partId }

                            PartInspectionScreen(
                                equipment = activeEq,
                                part = activePart,
                                allParts = allParts,
                                templateConfig = templateConfig,
                                candidatesByField = candidatesByField,
                                ocrState = ocrState,
                                isSaving = isSaving,
                                onBack = { navController.popBackStack() },
                                onSelectPart = { targetPart ->
                                    navController.navigate("part_inspect/$equipmentId/${targetPart.id}") {
                                        popUpTo("equipment_parts/$equipmentId") { inclusive = false }
                                    }
                                },
                                onAnalyzeAndPersistPhoto = { uri, eqId, pId, partName, prevPath ->
                                    viewModel.persistAndAnalyzeSpectrometerPhoto(
                                        context = context,
                                        imageUri = uri,
                                        equipmentId = eqId,
                                        partId = pId,
                                        partName = partName,
                                        previousPhotoPath = prevPath
                                    )
                                },
                                onResetOcr = { viewModel.resetOcrState() },
                                onDeleteCandidate = { key, value -> viewModel.deleteCandidate(key, value) },
                                onSaveAndStayOrBack = { updatedPart, goToNext ->
                                    viewModel.savePartInspection(
                                        equipmentId = equipmentId,
                                        updatedPart = updatedPart,
                                        allParts = allParts
                                    ) {
                                        if (goToNext) {
                                            val updatedList = allParts.map {
                                                if (it.id == updatedPart.id) updatedPart else it
                                            }
                                            val nextId = viewModel.findNextPartId(updatedList, updatedPart.id)
                                            if (nextId != null) {
                                                navController.navigate("part_inspect/$equipmentId/$nextId") {
                                                    popUpTo("equipment_parts/$equipmentId") { inclusive = false }
                                                }
                                            } else {
                                                navController.popBackStack()
                                            }
                                        } else {
                                            navController.popBackStack()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
