package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class EncodedReceiptProof(
    val base64: String,
    val fileName: String,
    val mimeType: String,
    val previewBytes: ByteArray
)

object ReceiptProofEncoder {

    private const val MAX_DIM_PX = 1080
    private const val MAX_BASE64_LENGTH = 420_000

    suspend fun encodeUri(context: Context, uri: Uri): Result<EncodedReceiptProof> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val rawMime = resolver.getType(uri).orEmpty().lowercase()
            val fileName = resolveFileName(context, uri)
            val isPdf = rawMime.contains("pdf") || fileName.lowercase().endsWith(".pdf")

            val bitmap = if (isPdf) {
                renderFirstPdfPage(context, uri)
            } else {
                decodeScaledImage(context, uri)
            }

            requireNotNull(bitmap) { "Unable to decode the selected payment receipt file." }
            val scaled = scaleBitmapIfNeeded(bitmap, MAX_DIM_PX)

            var quality = 82
            var compressed: ByteArray
            var encoded: String
            while (true) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                compressed = out.toByteArray()
                encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
                if (encoded.length <= MAX_BASE64_LENGTH || quality <= 40) break
                quality -= 12
            }

            if (scaled !== bitmap) bitmap.recycle()
            scaled.recycle()

            val baseName = fileName.substringBeforeLast('.', fileName)
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .trim('_')
                .ifBlank { "transfer_receipt" }
            val normalizedName = "$baseName.jpg"

            EncodedReceiptProof(
                base64 = encoded,
                fileName = normalizedName,
                mimeType = "image/jpeg",
                previewBytes = compressed
            )
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                val name = cursor.getString(idx)
                if (!name.isNullOrBlank()) return name
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "payment_receipt.jpg" } ?: "payment_receipt.jpg"
    }

    private fun renderFirstPdfPage(context: Context, uri: Uri): Bitmap? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount <= 0) return@use null
                renderer.openPage(0).use { page ->
                    val longest = max(page.width, page.height).coerceAtLeast(1)
                    val scale = (MAX_DIM_PX.toFloat() / longest.toFloat()).coerceAtMost(2f)
                    val targetW = (page.width * scale).roundToInt().coerceIn(1, MAX_DIM_PX)
                    val targetH = (page.height * scale).roundToInt().coerceIn(1, MAX_DIM_PX)
                    val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }

    private fun decodeScaledImage(context: Context, uri: Uri): Bitmap? {
        val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, optsBounds)
        }
        val maxSide = max(optsBounds.outWidth, optsBounds.outHeight)
        var sampleSize = 1
        while (maxSide > 0 && maxSide / sampleSize > MAX_DIM_PX * 2) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return decoded
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded && !decoded.isRecycled) {
            decoded.recycle()
        }
        return rotated
    }

    private fun scaleBitmapIfNeeded(source: Bitmap, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = max(w, h)
        if (longest <= maxDim) return source
        val ratio = maxDim.toFloat() / longest.toFloat()
        val newW = (w * ratio).roundToInt().coerceAtLeast(1)
        val newH = (h * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }
}
