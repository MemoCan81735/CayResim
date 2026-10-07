package app.cayresim.core.entity

/** Ein Foto einer Serie. */
data class SeriesPhoto(val uri: String, val takenAtMillis: Long)

/**
 * Regeln einer Vorher-Nachher-Serie (Geister-Overlay):
 * - Name 1 bis 40 Zeichen, ohne fuehrende oder folgende Leerzeichen.
 * - Fotos chronologisch, jedes Foto hoechstens einmal, hoechstens [MAX_PHOTOS].
 * - Das Overlay zeigt immer das juengste Foto.
 */
class SeriesEntity private constructor(val id: Long, name: String, photos: List<SeriesPhoto>) {
    var name: String = name; private set
    private val _photos = photos.sortedBy { it.takenAtMillis }.distinctBy { it.uri }.toMutableList()
    val photos: List<SeriesPhoto> get() = _photos.toList()
    val latest: SeriesPhoto? get() = _photos.lastOrNull()

    fun rename(newName: String): Boolean {
        val n = normalize(newName) ?: return false
        name = n; return true
    }

    /** false, wenn das Foto schon da ist oder die Serie voll ist. */
    fun add(photo: SeriesPhoto): Boolean {
        if (_photos.any { it.uri == photo.uri } || _photos.size >= MAX_PHOTOS) return false
        _photos += photo
        _photos.sortBy { it.takenAtMillis }
        return true
    }

    fun remove(uri: String): Boolean = _photos.removeAll { it.uri == uri }

    companion object {
        const val MAX_PHOTOS = 2_000
        const val MAX_NAME = 40

        fun normalize(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME }

        /** null, wenn der Name ungueltig ist. */
        fun create(id: Long, name: String, photos: List<SeriesPhoto> = emptyList()): SeriesEntity? =
            normalize(name)?.let { SeriesEntity(id, it, photos.take(MAX_PHOTOS)) }
    }
}
