package com.bitacora.timer

// Set curado de íconos para categorías (siluetas blancas con alfa real, mismo patrón
// que ic_notif.xml — se tiñen en runtime con setColorFilter, nunca se usan tal cual).
// El id vacío ("") significa "sin ícono": la UI cae al monograma (inicial del nombre).
object CategoryIcons {
    val MAP: LinkedHashMap<String, Int> = linkedMapOf(
        "book" to R.drawable.ic_cat_book,
        "school" to R.drawable.ic_cat_school,
        "work" to R.drawable.ic_cat_work,
        "flask" to R.drawable.ic_cat_flask,
        "code" to R.drawable.ic_cat_code,
        "pencil" to R.drawable.ic_cat_pencil,
        "fitness" to R.drawable.ic_cat_fitness,
        "music" to R.drawable.ic_cat_music,
        "globe" to R.drawable.ic_cat_globe,
        "folder" to R.drawable.ic_cat_folder
    )
    val ORDER: List<String> = MAP.keys.toList()
    fun resOf(key: String): Int? = MAP[key]
}
