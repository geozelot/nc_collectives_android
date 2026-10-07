package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The custom emoji field sent whatever was in it — a pasted paragraph
 * became the page's "emoji" on the server, shown in every list. It now
 * sends the first user-perceived character, compound emoji kept whole.
 */
class FirstGraphemeTest {
    @Test
    fun aSingleEmoji_isKept() {
        assertEquals("🙂", firstGrapheme("🙂"))
    }

    @Test
    fun aCompoundEmoji_staysWhole() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(family, firstGrapheme(family))
        assertEquals("🇩🇪", firstGrapheme("🇩🇪 Berlin"))
        assertEquals("👍🏽", firstGrapheme("👍🏽👍"))
    }

    @Test
    fun textAfterTheFirstCharacter_isDropped() {
        assertEquals("📓", firstGrapheme("  📓 my notebook  "))
    }

    @Test
    fun nothingButSpace_isNothing() {
        assertNull(firstGrapheme("   "))
    }
}
