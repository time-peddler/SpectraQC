package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ComponentInspection
import com.example.model.PartStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ValveDiagramCard(
    parts: List<ComponentInspection>,
    onCalloutPartSelected: (ComponentInspection) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val calloutParts = remember(parts) {
        parts.filter { it.calloutNumber in 1L..13L }.sortedBy { it.calloutNumber }
    }
    if (calloutParts.isEmpty()) return

    val verifiedColor = MaterialTheme.colorScheme.secondary
    val pendingColor = MaterialTheme.colorScheme.tertiary
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "一、设备部件解析图参考 (1-13号位)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "点击展开球阀结构示意图或直接点击编号跳转检测",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起解析图" else "展开解析图",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                // Blueprint-style schematic Canvas of O-type ball valve assembly
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(1.dp, outlineColor, RoundedCornerShape(12.dp))
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val w = size.width
                        val h = size.height
                        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

                        // Centerline axis
                        drawLine(
                            color = primaryColor.copy(alpha = 0.4f),
                            start = Offset(w * 0.08f, h * 0.62f),
                            end = Offset(w * 0.92f, h * 0.62f),
                            strokeWidth = 2f,
                            pathEffect = dash
                        )
                        drawLine(
                            color = primaryColor.copy(alpha = 0.4f),
                            start = Offset(w * 0.50f, h * 0.08f),
                            end = Offset(w * 0.50f, h * 0.92f),
                            strokeWidth = 2f,
                            pathEffect = dash
                        )

                        // Left Flange & Valve Cavity (#1, #13)
                        drawRoundRect(
                            color = outlineColor,
                            topLeft = Offset(w * 0.14f, h * 0.36f),
                            size = Size(w * 0.14f, h * 0.52f),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 3f)
                        )
                        // Valve Body (#2)
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(w * 0.32f, h * 0.34f),
                            size = Size(w * 0.36f, h * 0.56f),
                            cornerRadius = CornerRadius(12f, 12f),
                            style = Stroke(width = 4f)
                        )
                        // Ball Core (#3)
                        drawCircle(
                            color = verifiedColor,
                            radius = h * 0.19f,
                            center = Offset(w * 0.50f, h * 0.62f),
                            style = Stroke(width = 4f)
                        )
                        // Valve Seats (#4)
                        drawRect(
                            color = pendingColor,
                            topLeft = Offset(w * 0.39f, h * 0.46f),
                            size = Size(w * 0.03f, h * 0.32f),
                            style = Stroke(width = 3f)
                        )
                        drawRect(
                            color = pendingColor,
                            topLeft = Offset(w * 0.58f, h * 0.46f),
                            size = Size(w * 0.03f, h * 0.32f),
                            style = Stroke(width = 3f)
                        )
                        // Valve Stem (#5)
                        drawRoundRect(
                            color = primaryColor,
                            topLeft = Offset(w * 0.47f, h * 0.14f),
                            size = Size(w * 0.06f, h * 0.28f),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Stroke(width = 3.5f)
                        )
                        // Packing Gland (#7,#8,#9,#10)
                        drawRoundRect(
                            color = outlineColor,
                            topLeft = Offset(w * 0.41f, h * 0.20f),
                            size = Size(w * 0.18f, h * 0.10f),
                            cornerRadius = CornerRadius(4f, 4f),
                            style = Stroke(width = 3f)
                        )
                        // Right Body Flange (#6, #11, #12)
                        drawRoundRect(
                            color = outlineColor,
                            topLeft = Offset(w * 0.72f, h * 0.36f),
                            size = Size(w * 0.14f, h * 0.52f),
                            cornerRadius = CornerRadius(6f, 6f),
                            style = Stroke(width = 3f)
                        )
                    }

                    // Legend overlay
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LegendPill("已测", verifiedColor)
                        LegendPill("待测", pendingColor)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    calloutParts.forEach { part ->
                        val isDone = part.resolvedStatus != PartStatus.PENDING
                        val badgeBg = if (isDone) verifiedColor.copy(alpha = 0.15f) else pendingColor.copy(alpha = 0.14f)
                        val badgeFg = if (isDone) verifiedColor else pendingColor

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(badgeBg)
                                .border(1.dp, badgeFg.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable { onCalloutPartSelected(part) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(badgeFg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = part.calloutNumber.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = part.partName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendPill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
