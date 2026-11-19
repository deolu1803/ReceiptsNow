package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ReceiptTemplateType
import com.example.ui.theme.BurnishedGold
import com.example.ui.theme.NigerianEmerald
import com.example.ui.theme.SovereignNavy
import com.example.ui.theme.parseHexColorOrNull

@Composable
fun ReceiptTemplateSelectorCarousel(
    selectedTemplate: ReceiptTemplateType,
    primaryHex: String,
    secondaryHex: String,
    accentHex: String,
    allowedTemplates: List<ReceiptTemplateType> = ReceiptTemplateType.entries,
    onSelectTemplate: (ReceiptTemplateType) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = parseHexColorOrNull(primaryHex) ?: SovereignNavy
    val secondary = parseHexColorOrNull(secondaryHex) ?: NigerianEmerald
    val accent = parseHexColorOrNull(accentHex) ?: BurnishedGold

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ReceiptTemplateType.entries.forEach { tpl ->
            val isAllowed = tpl in allowedTemplates
            val isSelected = selectedTemplate == tpl
            Surface(
                onClick = { if (isAllowed) onSelectTemplate(tpl) },
                enabled = isAllowed,
                shape = RoundedCornerShape(18.dp),
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    !isAllowed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .width(176.dp)
                    .testTag("template_card_${tpl.name.lowercase()}")
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TemplateMiniPreview(
                        template = tpl,
                        primary = primary,
                        secondary = secondary,
                        accent = accent
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = tpl.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isAllowed) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "Upgrade required",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        } else if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected template",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Text(
                        text = if (isAllowed) tpl.subtitle else "Upgrade plan to unlock",
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

@Composable
private fun TemplateMiniPreview(
    template: ReceiptTemplateType,
    primary: Color,
    secondary: Color,
    accent: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFBFCFE),
        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(68.dp)) {
            val w = size.width
            val h = size.height
            when (template) {
                ReceiptTemplateType.STANDARD -> {
                    drawRect(color = primary, topLeft = Offset.Zero, size = Size(w * 0.05f, h))
                    drawRect(color = accent, topLeft = Offset(w * 0.05f, 0f), size = Size(w * 0.015f, h))
                    drawRoundRect(
                        color = primary.copy(alpha = 0.14f),
                        topLeft = Offset(w * 0.11f, h * 0.14f),
                        size = Size(w * 0.81f, h * 0.24f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        color = secondary.copy(alpha = 0.22f),
                        topLeft = Offset(w * 0.11f, h * 0.48f),
                        size = Size(w * 0.52f, h * 0.36f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.67f, h * 0.48f),
                        size = Size(w * 0.25f, h * 0.36f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }

                ReceiptTemplateType.PREMIUM_CORPORATE -> {
                    drawRect(color = primary, topLeft = Offset.Zero, size = Size(w * 0.28f, h))
                    drawRect(color = accent, topLeft = Offset(w * 0.28f, 0f), size = Size(w * 0.02f, h))
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = h * 0.13f,
                        center = Offset(w * 0.14f, h * 0.28f)
                    )
                    drawRoundRect(
                        color = secondary.copy(alpha = 0.2f),
                        topLeft = Offset(w * 0.35f, h * 0.16f),
                        size = Size(w * 0.57f, h * 0.22f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.35f, h * 0.48f),
                        size = Size(w * 0.57f, h * 0.36f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }

                ReceiptTemplateType.COMPACT -> {
                    drawRect(color = primary, topLeft = Offset.Zero, size = Size(w, h * 0.26f))
                    drawRect(color = accent, topLeft = Offset(0f, h * 0.26f), size = Size(w, h * 0.04f))
                    drawRoundRect(
                        color = secondary.copy(alpha = 0.2f),
                        topLeft = Offset(w * 0.08f, h * 0.38f),
                        size = Size(w * 0.84f, h * 0.24f),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.08f, h * 0.69f),
                        size = Size(w * 0.84f, h * 0.18f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }

                ReceiptTemplateType.MODERN_CLASSIC -> {
                    drawRect(color = primary, topLeft = Offset.Zero, size = Size(w, h * 0.08f))
                    drawRect(color = accent, topLeft = Offset(0f, h * 0.08f), size = Size(w, h * 0.03f))
                    drawCircle(
                        color = primary.copy(alpha = 0.22f),
                        radius = h * 0.11f,
                        center = Offset(w * 0.15f, h * 0.27f)
                    )
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(w * 0.08f, h * 0.42f),
                        size = Size(w * 0.84f, h * 0.2f),
                        cornerRadius = CornerRadius(10f, 10f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.08f, h * 0.68f),
                        size = Size(w * 0.4f, h * 0.22f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.52f, h * 0.68f),
                        size = Size(w * 0.4f, h * 0.22f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }

                ReceiptTemplateType.ROYAL_CERTIFICATE -> {
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(6f, 6f),
                        size = Size(w - 12f, h - 12f),
                        cornerRadius = CornerRadius(8f, 8f),
                        style = Stroke(width = 3f)
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(12f, 12f),
                        size = Size(w - 24f, h - 24f),
                        cornerRadius = CornerRadius(6f, 6f),
                        style = Stroke(width = 1.5f)
                    )
                    drawLine(
                        color = primary.copy(alpha = 0.35f),
                        start = Offset(w * 0.12f, h * 0.36f),
                        end = Offset(w * 0.88f, h * 0.36f),
                        strokeWidth = 2f
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.12f, h * 0.46f),
                        size = Size(w * 0.76f, h * 0.34f),
                        cornerRadius = CornerRadius(4f, 4f)
                    )
                }

                ReceiptTemplateType.FINTECH_WAVE -> {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(w, 0f)
                        lineTo(w, h * 0.36f)
                        cubicTo(w * 0.68f, h * 0.22f, w * 0.32f, h * 0.52f, 0f, h * 0.34f)
                        close()
                    }
                    drawPath(path = path, color = primary)
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(w * 0.1f, h * 0.32f),
                        size = Size(w * 0.8f, h * 0.24f),
                        cornerRadius = CornerRadius(12f, 12f)
                    )
                    drawRoundRect(
                        color = secondary.copy(alpha = 0.2f),
                        topLeft = Offset(w * 0.1f, h * 0.64f),
                        size = Size(w * 0.38f, h * 0.24f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                    drawRoundRect(
                        color = Color(0xFFE2E8F0),
                        topLeft = Offset(w * 0.52f, h * 0.64f),
                        size = Size(w * 0.38f, h * 0.24f),
                        cornerRadius = CornerRadius(8f, 8f)
                    )
                }
            }
        }
    }
}
