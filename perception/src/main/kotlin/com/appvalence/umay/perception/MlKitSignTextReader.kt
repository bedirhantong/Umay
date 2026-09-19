package com.appvalence.umay.perception

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device text recognition (Latin script — covers Turkish, including
 * İ/ı/Ğ/ğ/Ş/ş/Ö/ö/Ü/ü, though a script trained mostly on unaccented text can
 * still misread the Turkish-specific letters occasionally). Its model
 * downloads once via Google Play Services the first time it's used, then
 * never calls out again: free, and works with no connection at all — unlike
 * [com.appvalence.umay.cloud], nothing here needs the internet or a
 * billing account.
 *
 * A read failing (model not ready yet, a genuinely unreadable frame) is
 * reported as no text found, not an exception: this is a "nice to have"
 * secondary feature layered on top of navigation, which must keep working
 * regardless.
 */
class MlKitSignTextReader : SignTextReader {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun read(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.map { it.text }
                continuation.resume(SignTextFormatting.clean(blocks))
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Text recognition failed", error)
                continuation.resume(emptyList())
            }
    }

    fun close() {
        recognizer.close()
    }

    private companion object {
        const val TAG = "MlKitSignTextReader"
    }
}
