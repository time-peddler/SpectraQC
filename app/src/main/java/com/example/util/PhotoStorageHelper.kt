package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object PhotoStorageHelper {

    private const val PHOTOS_DIR = "inspection_photos"
    private const val MAX_DIMENSION = 1280
    private const val JPEG_QUALITY = 85

    /**
     * Copies, auto-rotates (EXIF), scales, and persists a spectrometer photo into
     * app-private storage (`filesDir/inspection_photos/`) so it survives cache clears
     * and can be embedded directly into exported .xlsx workbooks offline.
     */
    suspend fun persistInspectionPhoto(
        context: Context,
        sourceUri: Uri,
        equipmentId: String,
        partId: String,
        previousPhotoPath: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, PHOTOS_DIR).apply { mkdirs() }
            val destFile = File(dir, "part_${equipmentId.take(8)}_${partId.take(8)}_${System.currentTimeMillis()}.jpg")

            val decoded = decodeOrientedBitmap(context, sourceUri, MAX_DIMENSION)
                ?: return@withContext Result.failure(IllegalArgumentException("无法读取照片内容，请重新拍摄或选图"))

            try {
                FileOutputStream(destFile).use { fos ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
                    fos.flush()
                }
            } finally {
                if (!decoded.isRecycled) {
                    decoded.recycle()
                }
            }

            if (previousPhotoPath.isNotBlank() && previousPhotoPath != destFile.absolutePath) {
                runCatching {
                    val old = File(previousPhotoPath)
                    if (old.exists() && old.parentFile?.name == PHOTOS_DIR) {
                        old.delete()
                    }
                }
            }

            Result.success(destFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deletePhotoIfOwned(photoPath: String) {
        if (photoPath.isBlank()) return
        runCatching {
            val f = File(photoPath)
            if (f.exists() && f.parentFile?.name == PHOTOS_DIR) {
                f.delete()
            }
        }
    }

    private fun decodeOrientedBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sample = 1
        while (max(rawW / sample, rawH / sample) > maxDim) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return null

        val rotationDegrees = readExifRotationDegrees(context, uri)
        if (rotationDegrees == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated != rawBitmap && !rawBitmap.isRecycled) {
            rawBitmap.recycle()
        }
        return rotated
    }

    private fun readExifRotationDegrees(context: Context, uri: Uri): Float {
        return try {
            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
    }
}
