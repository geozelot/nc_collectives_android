package com.megamaced.nccollectives.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.megamaced.nccollectives.data.auth.AuthState
import com.megamaced.nccollectives.data.auth.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * WorkManager wrapper around [FullSync]. Runs both as the periodic pull and
 * as the one-shot fired when the app comes to the foreground; the two differ
 * only in how they treat a transient failure (see [KEY_ONE_SHOT]).
 */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        private val fullSync: FullSync,
        private val sessionManager: SessionManager,
    ) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            // B-105: only for a live session. The account wipe cancels the
            // work it can see, but a run can still start after it begins:
            // sign-out's clearAll() resets the cadence and the collector
            // re-enqueues the periodic sync, and every foreground enqueues
            // a sync and both flushes. Such a run would pass the issue #20
            // generation guard, which the wipe has already bumped, while
            // the outgoing credential is still in the store, and so write
            // that account's data into the cleared database. A switch,
            // sign-out or re-auth in progress is no session to work for.
            if (sessionManager.authState.value != AuthState.Authenticated) return Result.success()
            val isOneShot = inputData.getBoolean(KEY_ONE_SHOT, false)
            val outcome = fullSync.run()
            if (outcome is SyncOutcome.Retryable && isOneShot) {
                Timber.w("One-shot sync failed (%s); leaving it to the next foreground", outcome.message)
            }
            return when (retryDecision(outcome, isOneShot)) {
                SyncRetryDecision.Complete -> Result.success()
                SyncRetryDecision.Retry -> Result.retry()
            }
        }

        companion object {
            /**
             * Marks the foreground one-shot. Absent (false) means the periodic
             * run, which keeps WorkManager's retry/backoff semantics.
             */
            const val KEY_ONE_SHOT = "one_shot"
        }
    }

/** What a [SyncOutcome] means for WorkManager. */
internal enum class SyncRetryDecision { Complete, Retry }

/**
 * B-57: a one-shot foreground sync must never park itself in WorkManager's
 * retry backoff. The unique work name is held for the whole backoff window
 * (exponential, capped at five hours), and under the old `KEEP` policy every
 * later `syncNow()` was dropped against it — so a single bad network moment
 * silently disabled foreground sync for hours, which is the "stuck on the
 * state from setup" report. There is nothing to retry *for*: the next
 * foreground fires a fresh one, and the periodic worker covers the
 * background case.
 *
 * Everything else completes. `Failed` means the server answered and refused,
 * which the same request won't fix; `Unauthorised` is the session manager's
 * problem, not the worker's.
 */
internal fun retryDecision(
    outcome: SyncOutcome,
    isOneShot: Boolean,
): SyncRetryDecision =
    when (outcome) {
        is SyncOutcome.Retryable -> if (isOneShot) SyncRetryDecision.Complete else SyncRetryDecision.Retry
        SyncOutcome.Success, SyncOutcome.Unauthorised, is SyncOutcome.Failed -> SyncRetryDecision.Complete
    }
