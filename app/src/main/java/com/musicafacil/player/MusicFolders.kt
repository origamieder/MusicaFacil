package com.musicafacil.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Carpetas elegidas por el usuario (rutas absolutas). Vacío = todo el móvil. */
class FolderSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): List<String> = prefs.getStringSet(KEY, emptySet())!!.sorted()

    fun save(folders: List<String>) {
        prefs.edit().putStringSet(KEY, folders.toSet()).apply()
    }

    private companion object {
        const val KEY = "folders"
    }
}

/**
 * Convierte la carpeta elegida en el selector de Android ("primary:Music/FLAC")
 * en una ruta real ("/storage/emulated/0/Music/FLAC").
 */
fun folderPathFromTreeUri(uri: Uri): String? {
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    val volume = docId.substringBefore(':')
    val relative = docId.substringAfter(':', "").trim('/')
    val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
    return if (relative.isEmpty()) base else "$base/$relative"
}

/** Nombre legible: "Interno › Music › FLAC" o "Tarjeta SD › Música". */
fun folderLabel(path: String): String {
    val internal = "/storage/emulated/0"
    val (root, rest) = when {
        path.startsWith(internal) -> "Interno" to path.removePrefix(internal)
        path.startsWith("/storage/") -> "Tarjeta SD" to path.removePrefix("/storage/").substringAfter('/', "")
        else -> "" to path
    }
    val parts = listOf(root) + rest.split('/').filter { it.isNotEmpty() }
    return parts.filter { it.isNotEmpty() }.joinToString(" › ")
}

/** ¿Está el archivo dentro de alguna de las carpetas (o sus subcarpetas)? */
fun isInFolders(filePath: String?, folders: List<String>): Boolean {
    if (folders.isEmpty()) return true
    if (filePath == null) return false
    val p = filePath.lowercase()
    return folders.any { p.startsWith(it.lowercase().trimEnd('/') + "/") }
}
