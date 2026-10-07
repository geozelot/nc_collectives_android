package com.megamaced.nccollectives.ui.screen.collective

import com.megamaced.nccollectives.domain.model.PageListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * U11: what a drag-and-drop in the page tree means. The visible tree here:
 *
 *     A          (parent 1)
 *       a1       (parent A)
 *       a2       (parent A)
 *     B          (parent 1)
 */
class SiblingOrderAfterDropTest {
    private val nodes = listOf(node(10, parent = 1), node(11, parent = 10), node(12, parent = 10), node(20, parent = 1))

    @Test
    fun movingASiblingAboveAnother_isANewOrder() {
        assertEquals(listOf(20L, 10L), siblingOrderAfterDrop(nodes, movedPageId = 20, newVisibleOrder = listOf(20, 10, 11, 12)))
    }

    @Test
    fun droppingAmongAnotherRowsChildren_changesNothing() {
        // B dragged up between a1 and a2: its siblings are still [A, B], so
        // the server is told nothing, and the tree must snap back.
        assertNull(siblingOrderAfterDrop(nodes, movedPageId = 20, newVisibleOrder = listOf(10, 11, 20, 12)))
    }

    @Test
    fun droppingBackInPlace_changesNothing() {
        assertNull(siblingOrderAfterDrop(nodes, movedPageId = 20, newVisibleOrder = listOf(10, 11, 12, 20)))
    }

    private fun node(
        id: Long,
        parent: Long,
    ) = PageNode(
        page = PageListItem(
            id = id,
            collectiveId = 7,
            parentId = parent,
            title = "P$id",
            emoji = null,
            tags = emptyList(),
            subpageOrder = emptyList(),
            trashed = false,
            serverTimestamp = 0,
            lastUserDisplayName = "",
            hasDraft = false,
        ),
        hasChildren = false,
        isFavorite = false,
    )
}
