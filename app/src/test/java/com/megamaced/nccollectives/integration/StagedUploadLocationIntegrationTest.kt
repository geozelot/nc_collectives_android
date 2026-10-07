package com.megamaced.nccollectives.integration

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.db.entity.AttachmentEntity
import com.megamaced.nccollectives.data.repository.AttachmentRepositoryImpl
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Staged upload bytes are the only copy of a picked or shared file until the
 * upload lands — B-29 copies them because the source may vanish — and they
 * used to live in the cache directory. Android clears an app's cache under
 * storage pressure, typically while the app sits in the background waiting
 * for a network, which is exactly when a queued upload needs its bytes.
 */
@RunWith(AndroidJUnit4::class)
class StagedUploadLocationIntegrationTest {
    private lateinit var env: IntegrationEnvironment

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun anUploadIsStagedOutsideTheCache() =
        runTest {
            env.seedPage(id = 12)
            val source = File(env.context.filesDir, "picked.jpg").apply { writeText("bytes") }

            val name = env.attachmentRepository.enqueueUpload(12, Uri.fromFile(source), "photo.jpg", "image/jpeg")

            val row = env.db.attachmentDao().getById(AttachmentEntity.key(12, name!!))
            val staged = File(Uri.parse(row!!.localUriString).path!!)
            assertTrue("staged at $staged", staged.path.startsWith(env.context.noBackupFilesDir.path))
            assertEquals("bytes", staged.readText())
        }

    @Test
    fun anUploadStagedInTheCacheBeforeThis_isMovedAndRepointed() =
        runTest {
            env.seedPage(id = 12)
            val key = AttachmentEntity.key(12, "photo.jpg")
            val legacy = File(File(env.context.cacheDir, "attachments-pending"), key.replace('/', '_'))
            legacy.parentFile!!.mkdirs()
            legacy.writeText("waiting")
            env.db.attachmentDao().upsert(stagedRow(key, Uri.fromFile(legacy).toString()))

            AttachmentRepositoryImpl.resetLegacyMoveForTest()
            AttachmentRepositoryImpl.moveLegacyStaging(env.context, env.db.attachmentDao())

            val row = env.db.attachmentDao().getById(key)
            val moved = File(Uri.parse(row!!.localUriString).path!!)
            assertTrue("now at $moved", moved.path.startsWith(env.context.noBackupFilesDir.path))
            assertEquals("waiting", moved.readText())
            assertEquals(moved, AttachmentRepositoryImpl.stagedFileFor(env.context, key))
            assertFalse("nothing left in the cache", legacy.exists())
        }

    @Test
    fun theAccountWipe_removesStagedBytes() =
        runTest {
            val staged = AttachmentRepositoryImpl.stagedFileFor(env.context, AttachmentEntity.key(12, "photo.jpg"))
            staged.parentFile!!.mkdirs()
            staged.writeText("account A's file")

            AttachmentRepositoryImpl.clearCachedFiles(env.context)

            assertFalse(staged.exists())
        }

    private fun stagedRow(
        key: String,
        localUri: String,
    ) = AttachmentEntity(
        id = key,
        pageId = 12,
        fileName = "photo.jpg",
        contentType = "image/jpeg",
        size = 7,
        lastModifiedMs = 0,
        etag = null,
        status = AttachmentEntity.STATUS_PENDING,
        localUriString = localUri,
        lastSyncedAt = 0,
    ).also { assertNotNull(it) }
}
