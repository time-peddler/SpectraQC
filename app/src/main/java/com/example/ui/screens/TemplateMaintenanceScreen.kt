package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.FieldCategory
import com.example.model.TemplateFieldDef
import com.example.model.TemplateSchemaConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateMaintenanceScreen(
    templateConfig: TemplateSchemaConfig,
    onBack: () -> Unit,
    onToggleField: (fieldKey: String, enabled: Boolean) -> Unit,
    onAddField: (label: String, category: FieldCategory, enableWaterfall: Boolean, onDone: () -> Unit) -> Unit,
    onRemoveCustomField: (fieldKey: String) -> Unit,
    onResetDefault: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(FieldCategory.EQUIPMENT_INFO) }
    var newFieldLabel by remember { mutableStateOf("") }
    var enableWaterfall by remember { mutableStateOf(true) }
    var showResetConfirm by remember { mutableStateOf(false) }

    val fieldsForCategory = remember(templateConfig, selectedCategory) {
        templateConfig.fields
            .filter { it.category == selectedCategory }
            .sortedBy { it.sortOrder }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("模板字段维护", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "按需增减设备字段、化学元素列与评价列，导出 XLSX 自动同步",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("template_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("恢复默认")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FieldCategory.entries.forEach { cat ->
                        val activeCount = templateConfig.fields.count { it.category == cat && it.enabled }
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text("${cat.label} ($activeCount)") }
                        )
                    }
                }
            }

            // Add New Custom Field Card
            item {
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
                            text = "新增 ${selectedCategory.label}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = newFieldLabel,
                            onValueChange = { newFieldLabel = it },
                            label = {
                                Text(
                                    when (selectedCategory) {
                                        FieldCategory.EQUIPMENT_INFO -> "字段名称（如 公称口径、设计压力、制造厂商）"
                                        FieldCategory.ELEMENT -> "化学元素符号（如 C、S、P、Mg）"
                                        FieldCategory.PART_DETAIL -> "检测/评价列名称（如 炉批号、热处理状态、检验员）"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("new_template_field_input")
                        )

                        if (selectedCategory != FieldCategory.ELEMENT) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("启用输入时瀑布下拉候选", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "自动记忆该字段历史录入值，下次可重复点选",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = enableWaterfall,
                                    onCheckedChange = { enableWaterfall = it }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                onAddField(newFieldLabel, selectedCategory, enableWaterfall) {
                                    newFieldLabel = ""
                                }
                            },
                            enabled = newFieldLabel.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("add_template_field_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("添加到模板")
                        }
                    }
                }
            }

            item {
                Text(
                    text = "当前字段清单（开关控制是否在录入与导出 Excel 中显示）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            items(fieldsForCategory, key = { it.key }) { field ->
                TemplateFieldRow(
                    field = field,
                    onToggle = { enabled -> onToggleField(field.key, enabled) },
                    onDeleteCustom = { onRemoveCustomField(field.key) }
                )
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("恢复默认模板字段？") },
            text = { Text("将恢复标准客户模板字段（含 17 项化学元素及默认评价列），已保存的设备与零件历史数据不受影响。") },
            confirmButton = {
                Button(
                    onClick = {
                        onResetDefault()
                        showResetConfirm = false
                    }
                ) { Text("确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TemplateFieldRow(
    field: TemplateFieldDef,
    onToggle: (Boolean) -> Unit,
    onDeleteCustom: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val tagText = when {
                        field.isCoreRequired -> "核心必选"
                        field.isBuiltIn -> "标准模板"
                        else -> "自定义扩展"
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (field.isBuiltIn) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Text(
                            text = tagText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (field.enableWaterfallCandidates) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = "瀑布候选",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (!field.isBuiltIn) {
                IconButton(onClick = onDeleteCustom) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除自定义字段",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Switch(
                checked = field.enabled,
                onCheckedChange = onToggle,
                enabled = !field.isCoreRequired
            )
        }
    }
}
