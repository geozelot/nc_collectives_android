package com.megamaced.nccollectives.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the B-91 decision: a refresh deletes a page the server stopped listing
 * only when nothing on it, and nothing below it, is unsynced. The wiring
 * (which rows count as unsynced, the cascade, the transaction) is covered by
 * `RefreshReconcileIntegrationTest`.
 */
class ReconcilableDeletionsTest {
    // 1 is the landing page; 2 and 5 hang off it; 3 is under 2; 4 is under 3.
    private val tree = mapOf(1L to 0L, 2L to 1L, 3L to 2L, 4L to 3L, 5L to 1L)

    @Test
    fun nothingUnsynced_everythingUnlistedGoes() {
        assertEquals(setOf(3L, 4L), reconcilableDeletions(unlisted = listOf(3L, 4L), unsynced = emptyList(), parentOf = tree))
    }

    @Test
    fun anUnsyncedPage_staysWithItsAncestors_whileItsSiblingsGo() {
        // A collaborator trashed 2's subtree, and the user had an offline
        // edit on 4. Keeping 4 but not 3 and 2 would leave it unreachable,
        // because the tree is walked down from the landing page.
        assertEquals(
            setOf(5L),
            reconcilableDeletions(unlisted = listOf(2L, 3L, 4L, 5L), unsynced = listOf(4L), parentOf = tree),
        )
    }

    @Test
    fun descendantsOfAnUnsyncedPage_areNotProtected() {
        // Only the path *up* to the landing page is needed to reach the page;
        // its children hold nothing of the user's.
        assertEquals(
            setOf(4L),
            reconcilableDeletions(unlisted = listOf(3L, 4L), unsynced = listOf(3L), parentOf = tree),
        )
    }

    @Test
    fun anUnsyncedPageTheServerStillLists_protectsAnUnlistedAncestor() {
        // Odd, but cheap to get right: a listed page whose cached parent
        // the listing omits still needs that parent to be reachable.
        assertEquals(
            emptySet<Long>(),
            reconcilableDeletions(unlisted = listOf(2L), unsynced = listOf(3L), parentOf = tree),
        )
    }

    @Test
    fun aParentMissingFromTheCache_endsTheWalk() {
        assertEquals(
            setOf(9L),
            reconcilableDeletions(unlisted = listOf(8L, 9L), unsynced = listOf(8L), parentOf = mapOf(8L to 77L, 9L to 1L)),
        )
    }

    @Test
    fun aCycleInStaleRows_terminates() {
        assertEquals(
            setOf(9L),
            reconcilableDeletions(
                unlisted = listOf(6L, 7L, 9L),
                unsynced = listOf(6L),
                parentOf = mapOf(6L to 7L, 7L to 6L, 9L to 1L),
            ),
        )
    }
}
