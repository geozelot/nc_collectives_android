package com.megamaced.nccollectives.data.repository

import com.megamaced.nccollectives.data.db.dao.AttachmentDao
import com.megamaced.nccollectives.data.db.dao.EditQueueDao
import com.megamaced.nccollectives.data.db.dao.PageDao

/**
 * How many ids one reconcile `DELETE … IN (…)` binds. SQLite before 3.32,
 * which is what API 29–30 ship, rejects a statement with more than 999 bound
 * arguments, and minSdk is 29.
 */
internal const val RECONCILE_DELETE_CHUNK = 500

/**
 * B-91: which of [unlisted] (cached pages a server listing no longer names)
 * a refresh may delete.
 *
 * A listing tells us what the *server* has. Rows that hold only a copy of the
 * server's data can go when it stops listing them. Rows in [unsynced] can't:
 * their text or bytes exist nowhere else. The flush worker parks a queued
 * edit as a draft when the page 404s, and the conflict banner on that draft
 * is how the user gets the text back. Deleting the row deletes the banner
 * along with the text.
 *
 * The ancestors of an unsynced page stay too. The page tree is walked down
 * from the landing page, so a kept page whose parent was deleted would be one
 * nobody can open. [parentOf] maps a page id to its parent id; the landing
 * page's parent (0) is not a row, which ends the walk. The walk also stops at
 * any id it has already kept, so a cycle in stale cache rows terminates.
 */
internal fun reconcilableDeletions(
    unlisted: Collection<Long>,
    unsynced: Collection<Long>,
    parentOf: Map<Long, Long>,
): Set<Long> {
    val keep = HashSet<Long>()
    for (id in unsynced) {
        var current: Long? = id
        while (current != null && keep.add(current)) {
            current = parentOf[current]
        }
    }
    return unlisted.filterNotTo(LinkedHashSet()) { it in keep }
}

/**
 * Delete [pageIds] together with their attachment and edit-queue rows, which
 * Room won't cascade because no entity declares a foreign key (B-66). Chunked
 * under [RECONCILE_DELETE_CHUNK].
 *
 * Caller must already hold a transaction.
 */
internal suspend fun deletePagesWithTheirRows(
    pageIds: Collection<Long>,
    pageDao: PageDao,
    attachmentDao: AttachmentDao,
    editQueueDao: EditQueueDao,
) {
    for (chunk in pageIds.chunked(RECONCILE_DELETE_CHUNK)) {
        attachmentDao.deleteForPageIds(chunk)
        editQueueDao.deleteForPageIds(chunk)
        pageDao.deleteByIds(chunk)
    }
}
