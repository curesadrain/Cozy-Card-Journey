package com.cozycards.levels

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/** JSON (de)serialisation of chapter files. Platform independent: callers supply the text. */
object LevelCatalog {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        prettyPrint = true
        prettyPrintIndent = " "
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun fileName(chapter: Int) = "chapter_%02d.json".format(chapter)

    fun decodeChapter(text: String): ChapterFile = json.decodeFromString(ChapterFile.serializer(), text)

    fun encodeChapter(file: ChapterFile): String = json.encodeToString(ChapterFile.serializer(), file)

    /** Loads all chapters through [read] (e.g. Android assets or the file system). */
    fun loadAll(read: (String) -> String): List<LevelDefinition> =
        (1..Chapters.COUNT).flatMap { decodeChapter(read(fileName(it))).levels }.sortedBy { it.number }
}
