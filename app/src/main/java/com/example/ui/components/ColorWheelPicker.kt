package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SovereignNavy
import com.example.ui.theme.parseHexColorOrNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

object ColorWheelMath {

    fun hsvToColorInt(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((rf + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((gf + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((bf + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun colorIntToHex(colorInt: Int): String {
        val r = (colorInt ushr 16) and 0xFF
        val g = (colorInt ushr 8) and 0xFF
        val b = colorInt and 0xFF
        return "#%02X%02X%02X".format(r, g, b)
    }

    fun parseHexToHsv(hex: String?): FloatArray? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().removePrefix("#")
        val colorInt = runCatching {
            when (clean.length) {
                6 -> ("FF$clean").toLong(16).toInt()
                8 -> clean.toLong(16).toInt()
                else -> return null
            }
        }.getOrNull() ?: return null

        val r = ((colorInt ushr 16) and 0xFF) / 255f
        val g = ((colorInt ushr 8) and 0xFF) / 255f
        val b = (colorInt and 0xFF) / 255f

        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val sat = if (maxC == 0f) 0f else delta / maxC
        return floatArrayOf(hue, sat, maxC)
    }
}

enum class BrandColorRole(val label: String) {
    PRIMARY("Primary"),
    SECONDARY("Secondary"),
    ACCENT("Accent")
}

@Composable
fun BrandColorWheelCard(
    primaryHex: String,
    secondaryHex: String,
    accentHex: String,
    onPrimaryChange: (String) -> Unit,
    onSecondaryChange: (String) -> Unit,
    onAccentChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var activeRole by remember { mutableStateOf(BrandColorRole.PRIMARY) }
    val activeHex = when (activeRole) {
        BrandColorRole.PRIMARY -> primaryHex
        BrandColorRole.SECONDARY -> secondaryHex
        BrandColorRole.ACCENT -> accentHex
    }
    val onActiveHexChange: (String) -> Unit = when (activeRole) {
        BrandColorRole.PRIMARY -> onPrimaryChange
        BrandColorRole.SECONDARY -> onSecondaryChange
        BrandColorRole.ACCENT -> onAccentChange
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ColorLens,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        "Brand Color Wheel & Palette",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Tap any color role below, then spin the full-spectrum wheel or pick a preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Role selector pills with live swatch dots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple(BrandColorRole.PRIMARY, primaryHex, "role_primary"),
                    Triple(BrandColorRole.SECONDARY, secondaryHex, "role_secondary"),
                    Triple(BrandColorRole.ACCENT, accentHex, "role_accent")
                ).forEach { (role, hex, tag) ->
                    val selected = activeRole == role
                    val swatch = parseHexColorOrNull(hex) ?: SovereignNavy
                    Surface(
                        onClick = { activeRole = role },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("color_wheel_$tag")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(1.5.dp, Color.White, CircleShape)
                            )
                            Text(
                                text = role.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                            Text(
                                text = hex.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            HsvColorWheelPicker(
                selectedHex = activeHex,
                roleLabel = activeRole.label,
                onHexSelected = onActiveHexChange
            )

            Text(
                "Quick Curated Executive Palettes",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                listOf(
                    Triple("#0B203B", "#047857", "#C89B3C"),
                    Triple("#064E3B", "#0F172A", "#D97706"),
                    Triple("#312E81", "#4F46E5", "#F59E0B"),
                    Triple("#7C2D12", "#B45309", "#FACC15"),
                    Triple("#881337", "#BE123C", "#FBBF24"),
                    Triple("#0F766E", "#0284C7", "#38BDF8"),
                    Triple("#1F2937", "#059669", "#EAB308")
                ).forEach { (p, s, a) ->
                    val isCurrent = primaryHex.equals(p, true) && secondaryHex.equals(s, true)
                    Surface(
                        onClick = {
                            onPrimaryChange(p)
                            onSecondaryChange(s)
                            onAccentChange(a)
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            if (isCurrent) 2.dp else 1.dp,
                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(p, s, a).forEach { hex ->
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(parseHexColorOrNull(hex) ?: SovereignNavy)
                                        .border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                                )
                            }
                            if (isCurrent) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Selected Palette",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HsvColorWheelPicker(
    selectedHex: String,
    roleLabel: String,
    onHexSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsedInitial = remember(selectedHex) {
        ColorWheelMath.parseHexToHsv(selectedHex) ?: floatArrayOf(214f, 0.81f, 0.23f)
    }
    var hue by remember { mutableFloatStateOf(parsedInitial[0]) }
    var saturation by remember { mutableFloatStateOf(parsedInitial[1]) }
    var brightness by remember { mutableFloatStateOf(parsedInitial[2]) }
    var hexInput by remember(selectedHex) { mutableStateOf(selectedHex.uppercase()) }

    LaunchedEffect(selectedHex) {
        val hsv = ColorWheelMath.parseHexToHsv(selectedHex)
        if (hsv != null) {
            val currentHex = ColorWheelMath.colorIntToHex(ColorWheelMath.hsvToColorInt(hue, saturation, brightness))
            if (!currentHex.equals(selectedHex, ignoreCase = true)) {
                hue = hsv[0]
                saturation = hsv[1]
                brightness = hsv[2]
            }
        }
        if (hexInput != selectedHex.uppercase()) {
            hexInput = selectedHex.uppercase()
        }
    }

    fun emitCurrentColor() {
        val colorInt = ColorWheelMath.hsvToColorInt(hue, saturation, brightness)
        val hex = ColorWheelMath.colorIntToHex(colorInt)
        hexInput = hex
        onHexSelected(hex)
    }

    val currentColor = Color(ColorWheelMath.hsvToColorInt(hue, saturation, brightness))
    val pureHueSatColor = Color(ColorWheelMath.hsvToColorInt(hue, saturation, 1f))
    val hueSweepColors = remember {
        listOf(
            Color(0xFFFF0000),
            Color(0xFFFFFF00),
            Color(0xFF00FF00),
            Color(0xFF00FFFF),
            Color(0xFF0000FF),
            Color(0xFFFF00FF),
            Color(0xFFFF0000)
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Full Spectrum Circular Color Wheel
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(216.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val r = size.width / 2f
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = offset.x - cx
                            val dy = offset.y - cy
                            val dist = hypot(dx, dy)
                            val clampedDist = dist.coerceAtMost(r)
                            val angleDeg = ((atan2(dy, dx) * 180.0 / PI).toFloat() + 360f) % 360f
                            hue = angleDeg
                            saturation = (clampedDist / r).coerceIn(0f, 1f)
                            emitCurrentColor()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val r = size.width / 2f
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val dx = change.position.x - cx
                            val dy = change.position.y - cy
                            val dist = hypot(dx, dy)
                            val clampedDist = dist.coerceAtMost(r)
                            val angleDeg = ((atan2(dy, dx) * 180.0 / PI).toFloat() + 360f) % 360f
                            hue = angleDeg
                            saturation = (clampedDist / r).coerceIn(0f, 1f)
                            emitCurrentColor()
                        }
                    }
                    .testTag("hsv_color_wheel_canvas")
            ) {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                // 1. Hue sweep gradient
                drawCircle(
                    brush = Brush.sweepGradient(colors = hueSweepColors, center = center),
                    radius = radius,
                    center = center
                )
                // 2. White center radial gradient for full saturation range (0% -> 100%)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
                // 3. Brightness darkening overlay so wheel reflects Value slider smoothly
                if (brightness < 1f) {
                    drawCircle(
                        color = Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.92f)),
                        radius = radius,
                        center = center
                    )
                }
                // 4. Outer crisp border ring
                drawCircle(
                    color = Color.White.copy(alpha = 0.65f),
                    radius = radius - 1.5f,
                    center = center,
                    style = Stroke(width = 3f)
                )

                // 5. Selector reticle handle
                val rad = (hue * PI / 180.0).toFloat()
                val handleDist = saturation * (radius - 12f)
                val hx = center.x + cos(rad) * handleDist
                val hy = center.y + sin(rad) * handleDist
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = 15f,
                    center = Offset(hx, hy),
                    style = Stroke(width = 5f)
                )
                drawCircle(
                    color = currentColor,
                    radius = 11f,
                    center = Offset(hx, hy)
                )
                drawCircle(
                    color = Color.White,
                    radius = 12.5f,
                    center = Offset(hx, hy),
                    style = Stroke(width = 3.5f)
                )
            }
        }

        // Brightness / Shade Slider with gradient visual track
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Shade / Brightness ($roleLabel)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${(brightness * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Color.Black, pureHueSatColor))
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            )
            Slider(
                value = brightness,
                onValueChange = { v ->
                    brightness = v.coerceIn(0.02f, 1f)
                    emitCurrentColor()
                },
                valueRange = 0.02f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = currentColor,
                    activeTrackColor = currentColor,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hsv_brightness_slider")
            )
        }

        // Selected Color Swatch Preview + Hex Input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = RoundedCornerShape(14.dp),
                color = currentColor,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
            ) {}
            OutlinedTextField(
                value = hexInput,
                onValueChange = { typed ->
                    hexInput = typed.uppercase().take(9)
                    val parsed = ColorWheelMath.parseHexToHsv(hexInput)
                    if (parsed != null) {
                        hue = parsed[0]
                        saturation = parsed[1]
                        brightness = parsed[2]
                        onHexSelected(ColorWheelMath.colorIntToHex(ColorWheelMath.hsvToColorInt(hue, saturation, brightness)))
                    }
                },
                label = { Text("$roleLabel Hex Code (#RRGGBB)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .testTag("hsv_hex_input")
            )
        }

        // Quick Basic Color Dots (White, Slate, Charcoal, Gold, Emerald, Red, Royal Blue, Violet)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "#0B203B", "#047857", "#C89B3C", "#DC2626",
                "#1D4ED8", "#7C3AED", "#DB2777", "#0F766E",
                "#EA580C", "#111827", "#475569", "#FFFFFF"
            ).forEach { dotHex ->
                val dotColor = parseHexColorOrNull(dotHex) ?: Color.Black
                val isSelected = selectedHex.equals(dotHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable {
                            hexInput = dotHex
                            ColorWheelMath.parseHexToHsv(dotHex)?.let { hsv ->
                                hue = hsv[0]
                                saturation = hsv[1]
                                brightness = hsv[2]
                            }
                            onHexSelected(dotHex)
                        }
                )
            }
        }
    }
}
