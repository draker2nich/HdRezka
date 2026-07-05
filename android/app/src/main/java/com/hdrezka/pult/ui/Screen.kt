package com.hdrezka.pult.ui

/** Экраны приложения. Навигация — простой стек в AppViewModel. */
sealed interface Screen {
    data object Home : Screen
    data object Devices : Screen
    data object Settings : Screen
    data object Remote : Screen

    /** Список результатов: поиск или категория. */
    data class Results(
        val title: String,
        val query: String? = null,
        val categoryPath: String? = null,
    ) : Screen

    data class Details(val url: String, val titleHint: String) : Screen
    data class Seasons(val translatorId: Int, val translatorName: String) : Screen
    data class Episodes(val translatorId: Int, val translatorName: String, val season: Int) : Screen
    data class Quality(
        val translatorId: Int,
        val translatorName: String,
        val season: Int?,
        val episode: Int?,
    ) : Screen
}
