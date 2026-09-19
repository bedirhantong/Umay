package com.appvalence.umay.common

import java.io.File

/**
 * A file plus the metadata a list row shows, read once off the main thread —
 * so drawing a row never touches the disk. Rows used to call lastModified()
 * and length() while composing, one pair of filesystem calls per row, every
 * time the screen was shown.
 */
data class StoredFile(val file: File, val name: String, val modifiedAtMs: Long, val sizeBytes: Long) {
    companion object {
        /** Disk I/O: call off the main thread. */
        fun of(files: List<File>): List<StoredFile> =
            files.map { StoredFile(it, it.nameWithoutExtension, it.lastModified(), it.length()) }
    }
}

/** Newest first, reading each file's modification time once rather than on every comparison. */
fun Array<File>.newestFirst(): List<File> =
    map { it to it.lastModified() }.sortedByDescending { it.second }.map { it.first }
