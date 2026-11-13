package com.musicafacil.player

/**
 * Navegación por carpetas a partir de las rutas de las canciones.
 * Las rutas son absolutas ("/storage/emulated/0/Music/Artista/Disco/01.flac").
 */
object Folders {

    /**
     * Carpetas de inicio: las elegidas en Ajustes o, si no hay, la raíz de cada almacenamiento.
     * Si una carpeta solo contiene otra carpeta (y ninguna canción), se baja directamente a esa.
     */
    fun roots(songs: List<Song>, chosen: List<String>): List<String> {
        val base = chosen.ifEmpty {
            songs.mapNotNull { it.path }.map { p ->
                when {
                    p.startsWith("/storage/emulated/0/") -> "/storage/emulated/0"
                    p.startsWith("/storage/") -> "/storage/" + p.removePrefix("/storage/").substringBefore('/')
                    else -> p.substringBeforeLast('/')
                }
            }.distinct()
        }
        return base.map { collapse(songs, it) }.distinct().sorted()
    }

    private fun collapse(songs: List<Song>, start: String): String {
        var path = start
        repeat(20) {
            val (subfolders, here) = contents(songs, path)
            if (here.isNotEmpty() || subfolders.size != 1) return path
            path = subfolders.first().path
        }
        return path
    }

    data class Folder(val path: String, val name: String, val songCount: Int)

    /** Subcarpetas (con su número de canciones, contando las de dentro) y canciones sueltas de [path]. */
    fun contents(songs: List<Song>, path: String): Pair<List<Folder>, List<Song>> {
        val prefix = path.trimEnd('/') + "/"
        val counts = HashMap<String, Int>()
        val here = ArrayList<Song>()
        for (s in songs) {
            val p = s.path ?: continue
            if (!p.startsWith(prefix)) continue
            val rest = p.substring(prefix.length)
            val slash = rest.indexOf('/')
            if (slash < 0) here += s else {
                val child = prefix + rest.substring(0, slash)
                counts[child] = (counts[child] ?: 0) + 1
            }
        }
        val folders = counts.map { (p, n) -> Folder(p, p.substringAfterLast('/'), n) }
            .sortedBy { it.name.lowercase() }
        return folders to here.sortedBy { it.path?.lowercase() }
    }

    /** Todas las canciones de [path] y sus subcarpetas, en orden de carpeta y archivo. */
    fun songsUnder(songs: List<Song>, path: String): List<Song> {
        val prefix = path.trimEnd('/') + "/"
        return songs.filter { it.path?.startsWith(prefix) == true }.sortedBy { it.path?.lowercase() }
    }

    /** Carpeta padre dentro de la navegación, o null si [path] ya es una carpeta de inicio. */
    fun parent(path: String, roots: List<String>): String? {
        if (path in roots) return null
        val up = path.substringBeforeLast('/', "")
        return up.takeIf { u -> roots.any { u.startsWith(it) } }
    }
}
