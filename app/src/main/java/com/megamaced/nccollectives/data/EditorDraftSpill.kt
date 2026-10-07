package com.megamaced.nccollectives.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * U17: editor drafts too large for saved state, parked on disk so they
 * survive process death without going through the Binder.
 *
 * Saved state crosses a Binder transaction that fails past roughly 1 MB for
 * the whole activity, and a String is parcelled as UTF-16 — two bytes a
 * character. A long page in the native editor took the app over that line
 * whenever it went to the background (opening the camera to attach a photo,
 * say), and `TransactionTooLargeException` crashed it.
 *
 * One file per page, overwritten at each save, under `noBackupFilesDir`: not
 * the cache directory, which the system may clear while the app is in the
 * background — exactly when the file is needed — and never backed up. The
 * editor deletes its file when it closes; [clearAll] is for sign-out, since
 * the files hold page text.
 */
@Singleton
class EditorDraftSpill
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** Write [text] for [pageId], and return the name to restore it by. */
        fun write(
            pageId: Long,
            text: String,
        ): String? {
            val file = fileFor(pageId)
            return try {
                file.parentFile?.mkdirs()
                file.writeText(text)
                file.name
            } catch (e: IOException) {
                Timber.w(e, "Couldn't park the editor draft for page %d", pageId)
                null
            }
        }

        /** The text parked under [name], or null when it is gone. */
        fun read(name: String): String? {
            val file = File(dir(context), name)
            if (file.parentFile != dir(context)) return null
            return try {
                if (file.isFile) file.readText() else null
            } catch (e: IOException) {
                Timber.w(e, "Couldn't read the parked editor draft %s", name)
                null
            }
        }

        fun delete(pageId: Long) {
            val file = fileFor(pageId)
            if (file.exists() && !file.delete()) Timber.w("Couldn't delete the parked editor draft for page %d", pageId)
        }

        private fun fileFor(pageId: Long) = File(dir(context), "page-$pageId.md")

        companion object {
            private fun dir(context: Context) = File(context.noBackupFilesDir, "editor-drafts")

            /** Delete every parked draft. Part of the account wipe. */
            fun clearAll(context: Context) {
                val dir = dir(context)
                if (dir.exists() && !dir.deleteRecursively()) Timber.w("Couldn't delete the parked editor drafts")
            }
        }
    }
