package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rename dialog's button and its keyboard Done used to apply different
 * rules: the button refused an unchanged title, Done sent it. One rule now,
 * for both.
 */
class RenameAllowedTest {
    @Test
    fun aNewTitle_isAllowed() {
        assertTrue(renameAllowed("New", current = "Old"))
    }

    @Test
    fun blankOrUnchanged_isNot() {
        assertFalse(renameAllowed("   ", current = "Old"))
        assertFalse(renameAllowed("Old", current = "Old"))
        assertFalse("surrounding spaces aren't a change", renameAllowed("  Old ", current = "Old"))
    }
}
