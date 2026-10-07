package com.megamaced.nccollectives.data.auth

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which account's data the local cache is allowed to hold.
 *
 * Bumped by [LocalDataWiper] before it clears anything, so a write issued
 * under the outgoing account can tell that it is no longer wanted. Issue #20:
 * cancelling work is not a barrier — `WorkManager.cancelUniqueWork` is
 * asynchronous, and `EditFlushWorker.recordPutOutcome` deliberately runs
 * `NonCancellable` (B-64, so a body the server accepted is never left with no
 * local record of it), which means cancellation *cannot* stop it even in
 * principle. A worker holding an account A response could therefore commit it
 * after the tables were cleared, and `AccountSwitcher` would then activate
 * account B on top of one account's pages, attachments and queued writes.
 *
 * The guard has to sit *inside* the same Room transaction as the write it
 * protects, which is what makes it airtight rather than merely narrow. Room
 * serialises transactions, and the wipe bumps the generation before opening
 * the one that clears the tables, so for any guarded write either:
 *
 *  - its transaction commits first, and the wipe's clear then removes what it
 *    wrote; or
 *  - its transaction starts after the bump, sees a stale generation, and
 *    abandons.
 *
 * There is no third ordering. Which writes need it: every one that follows
 * a network call (D7b). An upsert resurrects a wiped account's data. An
 * `UPDATE` or a `DELETE` matches no rows straight after the clear, but not
 * once the incoming account has synced: pages and attachments key on raw
 * server ids, so the outgoing account's page 17 is then the incoming
 * account's page 17. [commitIfCurrent] is the guard in one call.
 *
 * Process-wide rather than persisted on purpose. It answers "has the account
 * changed since this in-memory coroutine started", which has no meaning
 * across a process restart — and a restart has no in-flight writes to
 * arbitrate.
 */
@Singleton
class AccountGeneration
    @Inject
    constructor() {
        private val generation = AtomicLong(0)

        /**
         * Capture before issuing the network request whose response will be
         * written, not after it returns — the point is to notice a wipe that
         * happened while the request was in flight.
         */
        fun current(): Long = generation.get()

        /** Whether a write captured at [captured] may still be committed. */
        fun isCurrent(captured: Long): Boolean = generation.get() == captured

        /**
         * Invalidate every write issued under the current account. Called by
         * [LocalDataWiper] before it touches anything.
         */
        fun invalidate() {
            generation.incrementAndGet()
        }

        /**
         * Run [block] in one Room transaction, behind the check that makes it
         * a barrier against a wipe. Returns null, having written nothing,
         * when the account has changed since [captured].
         *
         * D7b: for the writes that follow a network call — capture before
         * the request, commit through this after it.
         */
        suspend fun <T> commitIfCurrent(
            database: RoomDatabase,
            captured: Long,
            block: suspend () -> T,
        ): T? =
            database.withTransaction {
                if (isCurrent(captured)) {
                    block()
                } else {
                    Timber.i("The account changed while a request was out; dropping the write that followed it")
                    null
                }
            }
    }

/**
 * What a call reports when [AccountGeneration.commitIfCurrent] dropped the
 * write that would have made its success true: a page created or copied on
 * the server but not cached, an edit neither saved nor queued.
 */
class AccountChangedException : IllegalStateException(ACCOUNT_CHANGED_MESSAGE)

const val ACCOUNT_CHANGED_MESSAGE = "The account changed before this was saved on the device"
