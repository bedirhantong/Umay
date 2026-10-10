package com.appvalence.umay.coremodel

import java.security.MessageDigest

/**
 * The id a building's map has on an institution map server (docs/SUNUCU.md):
 * readable where it can be, unique through a hash of the full name, and only
 * ever safe file-name characters. The server and the app derive it the same way.
 */
object InstitutionMapIds {
    fun idFor(buildingName: String): String {
        val readable = buildingName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').take(40).ifEmpty { "bina" }
        val hash = MessageDigest.getInstance("SHA-256").digest(buildingName.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        return "$readable-$hash"
    }
}
