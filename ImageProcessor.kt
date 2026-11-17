package com.example.localchat.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageProcessor(context: Context) {
    private val appContext = context.applicationContext
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun importAndRecognize(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val largestDimension = maxOf(info.size.width, info.size.height)
            if (largestDimension > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / largestDimension
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

        val directory = File(appContext.filesDir, "images").apply { mkdirs() }
        val file = File(directory, "${System.currentTimeMillis()}-${System.nanoTime()}.jpg")
        try {
            check(file.outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }) { "Could not save the selected image." }
            val recognizedText = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
            ).text
            file.absolutePath to recognizedText
        } catch (exception: Exception) {
            file.delete()
            throw exception
        } finally {
            bitmap.recycle()
        }
    }

    fun close() {
        recognizer.close()
    }

    private companion object {
        const val JPEG_QUALITY = 88
        const val MAX_IMAGE_DIMENSION = 2048
    }
}
