package com.megamaced.nccollectives.ui.screen.page

import com.megamaced.nccollectives.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U15: the trash confirmation says what trashing would lose. */
class TrashLossWarningTest {
    @Test
    fun everyKindOfUnsyncedWork_counts() {
        assertEquals(
            4,
            unsyncedWorkCount(
                hasQueuedEdit = true,
                hasDraft = true,
                attachmentStatuses = listOf(
                    Attachment.Status.PENDING,
                    Attachment.Status.FAILED,
                    Attachment.Status.REMOTE,
                ),
            ),
        )
    }

    @Test
    fun nothingToLose_saysNothing() {
        assertNull(trashLossWarning(unsyncedWorkCount(false, false, listOf(Attachment.Status.REMOTE))))
    }

    @Test
    fun somethingToLose_isSpelledOut() {
        assertTrue(trashLossWarning(1)!!.contains("won't bring it back"))
        assertTrue(trashLossWarning(3)!!.startsWith("It has 3 changes"))
    }
}
