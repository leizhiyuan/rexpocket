package me.leaver.rexpocket.data

import org.junit.Assert.*
import org.junit.Test

class FontCatalogTest {
    @Test fun catalogHasUniqueIdsCachesAndStyles() {
        val fonts = FontCatalog.fonts
        assertEquals(fonts.size, fonts.map { it.id }.toSet().size)
        assertEquals(fonts.size, fonts.map { it.cacheKey }.toSet().size)
        assertEquals(4, fonts.count { it.nerd })
        assertNull(FontCatalog.find(SYSTEM_FONT))
        assertNull(FontCatalog.find("unknown"))
        for (font in fonts) {
            assertEquals(400, font.assets.first().weight)
            assertFalse(font.assets.first().italic)
            assertEquals(font.assets.size, font.assets.map { it.weight to it.italic }.toSet().size)
            assertEquals(font.assets.size, font.assets.map { it.name }.toSet().size)
            assertTrue(font.assets.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
            assertTrue(font.source.startsWith("https://raw.githubusercontent.com/"))
        }
    }

    @Test fun existingJetBrainsCacheRemainsCompatible() {
        assertEquals("jetbrains-mono-v1", FontCatalog.find("jetbrains-mono")!!.cacheKey)
    }
}
