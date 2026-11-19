package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.sqrt

data class ExtractedBrandPalette(
    val primaryHex: String,
    val secondaryHex: String,
    val accentHex: String,
    val backgroundHex: String,
    val textHex: String
)

object BrandColorExtractor {

    fun extractPalette(bitmap: Bitmap): ExtractedBrandPalette {
        val buckets = mutableMapOf<Int, Int>()
        val sample = Bitmap.createScaledBitmap(bitmap, 72, 72, true)
        try {
            for (y in 0 until sample.height) {
                for (x in 0 until sample.width) {
                    val pixel = sample.getPixel(x, y)
                    val alpha = Color.alpha(pixel)
                    if (alpha < 128) continue

                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // Ignore pure white / near-white / pure black background noise for primary detection
                    val maxC = maxOf(r, g, b)
                    val minC = minOf(r, g, b)
                    if (maxC > 242 && minC > 235) continue

                    // Quantize to 5-bit per channel buckets
                    val qr = (r / 24) * 24
                    val qg = (g / 24) * 24
                    val qb = (b / 24) * 24
                    val key = Color.rgb(qr, qg, qb)
                    buckets[key] = (buckets[key] ?: 0) + 1
                }
            }
        } finally {
            if (sample !== bitmap && !sample.isRecycled) {
                sample.recycle()
            }
        }

        val sortedColors = buckets.entries
            .sortedByDescending { entry ->
                val c = entry.key
                val hsv = FloatArray(3)
                Color.colorToHSV(c, hsv)
                val saturationBoost = 1.0f + (hsv[1] * 1.4f)
                entry.value * saturationBoost
            }
            .map { it.key }

        val primary = sortedColors.firstOrNull { !isVeryLight(it) } ?: Color.parseColor("#054230")
        val secondary = sortedColors.firstOrNull { c ->
            colorDistance(c, primary) > 70.0 && !isVeryLight(c)
        } ?: shiftColor(primary, 0.78f)
        val accent = sortedColors.firstOrNull { c ->
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] > 0.35f && colorDistance(c, primary) > 60.0
        } ?: Color.parseColor("#D99B26")

        val backgroundHex = "#FFFFFF"
        val textHex = "#0F172A"

        return ExtractedBrandPalette(
            primaryHex = toHex(primary),
            secondaryHex = toHex(secondary),
            accentHex = toHex(accent),
            backgroundHex = backgroundHex,
            textHex = textHex
        )
    }

    fun encodeLogoBase64(bitmap: Bitmap, maxDim: Int = 512, maxBase64Chars: Int = 420_000): String {
        val candidateDims = listOf(maxDim, 384, 256, 192).distinct().filter { it > 0 }
        var lastEncoded = ""
        for (dim in candidateDims) {
            val scaled = scaleBitmapToMax(bitmap, dim)
            try {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.PNG, 95, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                lastEncoded = encoded
                if (encoded.length <= maxBase64Chars) {
                    return encoded
                }
            } finally {
                if (scaled !== bitmap && !scaled.isRecycled) {
                    scaled.recycle()
                }
            }
        }
        val finalScaled = scaleBitmapToMax(bitmap, candidateDims.lastOrNull() ?: 256)
        return try {
            for (quality in listOf(86, 72, 58)) {
                val out = ByteArrayOutputStream()
                finalScaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (encoded.length <= maxBase64Chars || quality == 58) {
                    return encoded
                }
            }
            lastEncoded
        } finally {
            if (finalScaled !== bitmap && !finalScaled.isRecycled) {
                finalScaled.recycle()
            }
        }
    }

    private fun scaleBitmapToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        return if (w > maxDim || h > maxDim) {
            val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
            Bitmap.createScaledBitmap(
                bitmap,
                (w * ratio).toInt().coerceAtLeast(1),
                (h * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
    }

    fun decodeBase64Bitmap(base64: String): Bitmap? {
        if (base64.isBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    fun decodeSampledBitmap(context: Context, uri: Uri, maxDim: Int = 1024): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val maxSide = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (maxSide > 0 && maxSide / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun toHex(colorInt: Int): String =
        String.format("#%06X", 0xFFFFFF and colorInt)

    fun parseColorSafe(hex: String, fallback: Int): Int {
        return try {
            Color.parseColor(hex.trim())
        } catch (_: Exception) {
            fallback
        }
    }

    fun contrastTextFor(bgColor: Int): Int {
        val r = Color.red(bgColor) / 255.0
        val g = Color.green(bgColor) / 255.0
        val b = Color.blue(bgColor) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 0.55) Color.parseColor("#0F172A") else Color.WHITE
    }

    private fun isVeryLight(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r > 235 && g > 235 && b > 235
    }

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun shiftColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
