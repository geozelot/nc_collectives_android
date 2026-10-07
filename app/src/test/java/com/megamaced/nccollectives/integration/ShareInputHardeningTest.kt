package com.megamaced.nccollectives.integration

import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.repository.copyAtMost
import com.megamaced.nccollectives.share.MAX_SAVED_TEXT_CHARS
import com.megamaced.nccollectives.share.SharePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** S-31: what another app can hand the exported share activity. */
@RunWith(AndroidJUnit4::class)
class ShareInputHardeningTest {
    private val own = "com.megamaced.nccollectives.debug"

    @Test
    fun ourOwnFileProvider_isNotASourceAnotherAppCanShareFrom() {
        val payload = SharePayload(
            text = "look",
            images = listOf(
                Uri.parse("content://$own.fileprovider/captures/capture-1.jpg"),
                Uri.parse("content://com.android.providers.media.documents/document/image%3A7"),
            ),
        )

        val accepted = payload.foreignOnly(own)

        assertEquals(listOf(Uri.parse("content://com.android.providers.media.documents/document/image%3A7")), accepted?.images)
        assertEquals("look", accepted?.text)
    }

    @Test
    fun aShareOfNothingButOurOwnFiles_isNoShare() {
        val payload = SharePayload(images = listOf(Uri.parse("content://$own.fileprovider/captures/x.jpg")))

        assertNull(payload.foreignOnly(own))
    }

    @Test
    fun aHugeText_isNotPutInTheSavedStateBundle() {
        // Binder refuses a transaction over about 1 MB; a long article shared
        // as text threw TransactionTooLargeException on every backgrounding.
        val state = Bundle()

        SharePayload(text = "x".repeat(MAX_SAVED_TEXT_CHARS + 1)).writeTo(state)

        assertNull(SharePayload.fromSavedState(state))
    }

    @Test
    fun theStagingCopy_stopsAtItsLimit() {
        val out = ByteArrayOutputStream()
        assertEquals(10L, copyAtMost(ByteArrayInputStream(ByteArray(10)), out, limit = 10))
        assertNull(copyAtMost(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), limit = 10))
    }
}
