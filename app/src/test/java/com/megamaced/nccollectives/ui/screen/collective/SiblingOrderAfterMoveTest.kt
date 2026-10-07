package com.megamaced.nccollectives.ui.screen.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The accessibility actions' reorder: one place at a time among siblings,
 * and nothing offered where there's nowhere to go.
 */
class SiblingOrderAfterMoveTest {
    private val siblings = listOf(1L, 2L, 3L)

    @Test
    fun movesOnePlace() {
        assertEquals(listOf(2L, 1L, 3L), siblingOrderAfterMove(siblings, 2, -1))
        assertEquals(listOf(1L, 3L, 2L), siblingOrderAfterMove(siblings, 2, 1))
    }

    @Test
    fun theEndsGoNowhere() {
        assertNull(siblingOrderAfterMove(siblings, 1, -1))
        assertNull(siblingOrderAfterMove(siblings, 3, 1))
    }

    @Test
    fun aPageThatIsNotASibling_goesNowhere() {
        assertNull(siblingOrderAfterMove(siblings, 9, 1))
    }
}
