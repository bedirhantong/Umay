package com.appvalence.umay.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the system share sheet for [files] (WhatsApp, Gmail, Drive, Nearby
 * Share, ...). Every file must live under a folder declared in
 * res/xml/file_paths.xml, or FileProvider refuses to hand it out.
 *
 * Returns false (never throws) if nothing could be shared — an empty list,
 * a file outside the shared folders, or no app able to receive it.
 */
fun shareFiles(context: Context, files: List<File>, mimeType: String, chooserTitle: String): Boolean {
    if (files.isEmpty()) return false
    return runCatching {
        val authority = "${context.packageName}.fileprovider"
        val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mimeType
        // Some receivers only honor the read grant when the URIs are also in ClipData.
        intent.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }.isSuccess
}
