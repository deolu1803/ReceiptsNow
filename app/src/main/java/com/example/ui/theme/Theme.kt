package com.example.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = ForestEmerald,
    onPrimary = Color.White,
    primaryContainer = ForestEmeraldDeep,
    onPrimaryContainer = Color.White,
    secondary = NigerianEmerald,
    onSecondary = Color.White,
    secondaryContainer = NigerianEmeraldContainer,
    onSecondaryContainer = OnNigerianEmeraldContainer,
    tertiary = BurnishedGold,
    onTertiary = Color.White,
    tertiaryContainer = BurnishedGoldSoft,
    onTertiaryContainer = OnBurnishedGoldSoft,
    background = MintCanvas,
    onBackground = CharcoalInk,
    surface = PureSurface,
    onSurface = CharcoalInk,
    surfaceVariant = MintTileSurface,
    onSurfaceVariant = MutedSlate,
    outline = MintBorder,
    error = AlertCrimson,
    onError = Color.White,
    errorContainer = AlertCrimsonContainer,
    onErrorContainer = Color(0xFF7F1D1D)
)

private val DarkColors = darkColorScheme(
    primary = MintVibrant,
    onPrimary = ForestEmeraldDeep,
    primaryContainer = Color(0xFF0A3326),
    onPrimaryContainer = Color(0xFFE2FFF3),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF002418),
    secondaryContainer = Color(0xFF0B4634),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2B1B00),
    tertiaryContainer = Color(0xFF3D2A07),
    onTertiaryContainer = BurnishedGoldSoft,
    background = Color(0xFF06120E),
    onBackground = Color(0xFFEAF7F1),
    surface = Color(0xFF0C1E17),
    onSurface = Color(0xFFEAF7F1),
    surfaceVariant = Color(0xFF122C22),
    onSurfaceVariant = Color(0xFF9AC2B3),
    outline = Color(0xFF1F4536),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF5C1515),
    onErrorContainer = Color(0xFFFEE2E2)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

fun Color.isLightColor(): Boolean = ColorUtils.calculateLuminance(this.toArgb()) > 0.5

private fun blendColor(from: Color, to: Int, ratio: Float): Color {
    return Color(ColorUtils.blendARGB(from.toArgb(), to, ratio.coerceIn(0f, 1f)))
}

@Composable
fun ReceiptsNowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    brandPrimaryHex: String? = null,
    brandAccentHex: String? = null,
    content: @Composable () -> Unit
) = Receipts1803Theme(
    darkTheme = darkTheme,
    brandPrimaryHex = brandPrimaryHex,
    brandAccentHex = brandAccentHex,
    content = content
)

@Composable
fun Receipts1803Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    brandPrimaryHex: String? = null,
    brandAccentHex: String? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = if (darkTheme) DarkColors else LightColors
    val customPrimary = parseHexColorOrNull(brandPrimaryHex)
    val customAccent = parseHexColorOrNull(brandAccentHex)

    val colorScheme = if (customPrimary != null) {
        if (!darkTheme) {
            val lum = ColorUtils.calculateLuminance(customPrimary.toArgb())
            val primaryTone = if (lum > 0.72) blendColor(customPrimary, AndroidColor.BLACK, 0.35f) else customPrimary
            val onPrimaryTone = if (primaryTone.isLightColor()) CharcoalInk else Color.White
            val containerTone = if (lum > 0.65) blendColor(primaryTone, AndroidColor.BLACK, 0.48f) else primaryTone
            val onContainerTone = if (containerTone.isLightColor()) CharcoalInk else Color.White
            val secondaryTone = customAccent?.let { acc ->
                if (ColorUtils.calculateLuminance(acc.toArgb()) > 0.72) blendColor(acc, AndroidColor.BLACK, 0.35f) else acc
            } ?: baseScheme.secondary
            baseScheme.copy(
                primary = primaryTone,
                onPrimary = onPrimaryTone,
                primaryContainer = containerTone,
                onPrimaryContainer = onContainerTone,
                secondary = secondaryTone
            )
        } else {
            val lum = ColorUtils.calculateLuminance(customPrimary.toArgb())
            val primaryTone = if (lum < 0.25) blendColor(customPrimary, AndroidColor.WHITE, 0.52f) else customPrimary
            val onPrimaryTone = if (primaryTone.isLightColor()) ForestEmeraldDeep else Color.White
            val containerTone = blendColor(customPrimary, 0xFF0A241B.toInt(), 0.58f)
            val onContainerTone = blendColor(customPrimary, AndroidColor.WHITE, 0.90f)
            val secondaryTone = customAccent?.let { acc ->
                if (ColorUtils.calculateLuminance(acc.toArgb()) < 0.22) blendColor(acc, AndroidColor.WHITE, 0.48f) else acc
            } ?: baseScheme.secondary
            baseScheme.copy(
                primary = primaryTone,
                onPrimary = onPrimaryTone,
                primaryContainer = containerTone,
                onPrimaryContainer = onContainerTone,
                secondary = secondaryTone
            )
        }
    } else {
        baseScheme
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

fun parseHexColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    return try {
        val clean = hex.trim().removePrefix("#")
        val colorInt = when (clean.length) {
            6 -> ("FF$clean").toLong(16).toInt()
            8 -> clean.toLong(16).toInt()
            else -> return null
        }
        Color(colorInt)
    } catch (_: Exception) {
        null
    }
}
