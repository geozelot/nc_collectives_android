package com.megamaced.nccollectives.util

import com.megamaced.nccollectives.data.api.RELEASE_REPO
import org.junit.Assert.assertEquals
import org.junit.Test

/** S-30: "Update available" only ever opens this repository's release page. */
class ReleasePageUrlTest {
    private val fallback = "https://github.com/$RELEASE_REPO/releases/latest"

    @Test
    fun theReleasePageTheApiNames_isKept() {
        val page = "https://github.com/$RELEASE_REPO/releases/tag/v2.13.0"
        assertEquals(page, releasePageUrl(page))
    }

    @Test
    fun anythingElse_fallsBackToTheRepositorysLatestRelease() {
        listOf(
            "https://evil.example/$RELEASE_REPO/releases/tag/v9",
            "http://github.com/$RELEASE_REPO/releases/tag/v9",
            "https://github.com/someone/else/releases/tag/v9",
            "https://github.com:8443/$RELEASE_REPO/releases/tag/v9",
            "https://github.com.evil.example/$RELEASE_REPO/releases/",
            "intent://scan#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "",
        ).forEach { url -> assertEquals(url, fallback, releasePageUrl(url)) }
    }
}
