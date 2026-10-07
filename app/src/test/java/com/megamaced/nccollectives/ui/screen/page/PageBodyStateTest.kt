package com.megamaced.nccollectives.ui.screen.page

import org.junit.Assert.assertEquals
import org.junit.Test

/** U4: what the page screen shows before it has a body. */
class PageBodyStateTest {
    @Test
    fun aPageNeverCached_whoseLoadFailed_showsTheError() {
        // The spinner used to win this case, forever.
        assertEquals(PageBodyState.Error, pageBodyState(hasPage = false, hasBody = false, isLoading = false, error = "not here"))
    }

    @Test
    fun whileLoading_itSpins() {
        assertEquals(PageBodyState.Loading, pageBodyState(hasPage = false, hasBody = false, isLoading = true, error = null))
        assertEquals(PageBodyState.Loading, pageBodyState(hasPage = true, hasBody = false, isLoading = true, error = null))
    }

    @Test
    fun aCachedBody_isShownWhateverTheRevalidationSaid() {
        assertEquals(PageBodyState.Content, pageBodyState(hasPage = true, hasBody = true, isLoading = false, error = "offline"))
        assertEquals(PageBodyState.Content, pageBodyState(hasPage = true, hasBody = true, isLoading = true, error = null))
    }
}
