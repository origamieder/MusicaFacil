package com.musicafacil.player

import android.content.Context

/**
 * Deja solo el artista principal: quita "feat. X", "ft. X" y "featuring X".
 * No toca "&" ni las comas, porque son parte del nombre de muchos grupos
 * (Simon & Garfunkel, Earth, Wind & Fire…).
 */
object Feat {
    private val artistRe = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.?|featuring)(\s+|$).*$""", RegexOption.IGNORE_CASE)
    private val titleBracketRe = Regex("""\s*[(\[]\s*(feat\.?|ft\.?|featuring)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val titleTailRe = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

    fun mainArtist(s: String): String = s.replace(artistRe, "").trim().ifEmpty { s }

    fun cleanTitle(s: String): String = s.replace(titleBracketRe, "").replace(titleTailRe, "").trim().ifEmpty { s }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("strip_feat", true)

    /** La canción solo con el artista principal (y marcada si había algo que quitar). */
    fun clean(song: Song): Song {
        val artist = mainArtist(song.artist)
        val albumArtist = mainArtist(song.albumArtist)
        val title = cleanTitle(song.title)
        if (artist == song.artist && albumArtist == song.albumArtist && title == song.title) return song
        return song.copy(artist = artist, albumArtist = albumArtist, title = title, featStripped = true)
    }
}
