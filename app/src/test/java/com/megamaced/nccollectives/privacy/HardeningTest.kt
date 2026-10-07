package com.megamaced.nccollectives.privacy

import android.content.ComponentName
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.MainActivity
import com.megamaced.nccollectives.data.db.NcCollectivesDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S9: two settings that are invisible until they matter, read from what the
 * app actually builds.
 */
@RunWith(AndroidJUnit4::class)
class HardeningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theLauncherActivityBelongsToNoTaskButItsOwn() {
        // Task hijacking (StrandHogg): another app declaring this package's
        // affinity can slip its own activity into this app's task on
        // Android 10 and 11, and be shown as this app.
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)

        assertTrue("affinity was ${info.taskAffinity}", info.taskAffinity.isNullOrEmpty())
    }

    @Test
    fun deletedRowsAreOverwrittenNotJustUnlinked() {
        // Without secure_delete, a deleted page's text stays in the
        // database file's free pages until something reuses them. The
        // platform's SQLite is built with it on; this is here for the day the
        // database moves to a driver that isn't.
        val db = Room
            .inMemoryDatabaseBuilder(context, NcCollectivesDatabase::class.java)
            .build()
        try {
            val value = db.openHelper.writableDatabase.query("PRAGMA secure_delete").use { cursor ->
                cursor.moveToFirst()
                cursor.getInt(0)
            }
            assertEquals(1, value)
        } finally {
            db.close()
        }
    }
}
