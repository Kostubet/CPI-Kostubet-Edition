package com.cosmod.launcherglass.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test fun publishedNotesUseTheAppLanguage() {
        val notes = """
            ### Русский
            - **Каталог** конфигов
            ### English
            - Config catalog
            [Full changelog](https://example.com)
        """.trimIndent()
        assertEquals(listOf("• Каталог конфигов"), ReleaseNotes.published(notes, "ru"))
        assertEquals(listOf("• Config catalog"), ReleaseNotes.published(notes, "en"))
        assertEquals(listOf("One language"), ReleaseNotes.published("One language", "ru"))
    }

    @Test fun repositoryNotesShowOnlyTheExactPublishedBuild() {
        val notes = """
            ## Русский
            ### Сборка 466 — релиз 0.3.3
            - Каталог
            ### Сборка 465
            - Старая сборка
            ## English
            ### Build 466 — 0.3.3 release
            - Catalog
            ### Build 465
            - Older
        """.trimIndent()
        assertEquals(listOf("• Каталог"), ReleaseNotes.fromChangelog(notes, 466, "ru"))
        assertEquals(listOf("• Catalog"), ReleaseNotes.fromChangelog(notes, 466, "en"))
        assertTrue(ReleaseNotes.fromChangelog(notes, 467, "en").isEmpty())
    }
}
