package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.api.dto.CollectiveDto
import com.megamaced.nccollectives.data.mapper.toEntity
import com.megamaced.nccollectives.data.repository.CollectiveRepositoryImpl
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder

/**
 * The server keeps a collective's favourites as one list and takes it whole.
 * The app built the list it sent from its own cache, so a favourite added on
 * the web since the last sync was removed the next time the phone starred
 * anything.
 */
@RunWith(AndroidJUnit4::class)
class FavoritesMergeIntegrationTest {
    private lateinit var env: IntegrationEnvironment
    private lateinit var dispatcher: RoutingDispatcher

    @Before
    fun setUp() {
        env = IntegrationEnvironment.create()
        dispatcher = RoutingDispatcher()
        env.server.dispatcher = dispatcher
    }

    @After
    fun tearDown() {
        env.close()
    }

    @Test
    fun starringAPage_keepsTheFavoritesAddedElsewhere() =
        runTest {
            env.db.collectiveDao().upsert(CollectiveDto(id = 7, name = "Wiki", userFavoritePages = listOf(1)).toEntity(0))
            dispatcher
                .on(
                    "/collectives",
                    OcsResponses.envelope("""{"collectives":[{"id":7,"name":"Wiki","userFavoritePages":[1,5]}]}"""),
                    method = "GET",
                ).on("/collectives/7", OcsResponses.envelope("[]"), method = "PUT")

            val result = collectives().toggleFavorite(collectiveId = 7, pageId = 9, favorite = true)

            assertTrue("was $result", result is ApiResult.Success)
            val sent = URLDecoder.decode(
                dispatcher
                    .requestsWithMethod("PUT")
                    .single()
                    .body
                    .readUtf8(),
                "UTF-8",
            )
            assertEquals("favoritePages=[1,5,9]", sent)
            assertEquals(
                "1,5,9",
                env.db
                    .collectiveDao()
                    .getById(7)
                    ?.userFavoritePagesCsv,
            )
        }

    @Test
    fun unstarringAPage_keepsTheFavoritesAddedElsewhere() =
        runTest {
            env.db.collectiveDao().upsert(CollectiveDto(id = 7, name = "Wiki", userFavoritePages = listOf(1, 9)).toEntity(0))
            dispatcher
                .on(
                    "/collectives",
                    OcsResponses.envelope("""{"collectives":[{"id":7,"name":"Wiki","userFavoritePages":[1,5,9]}]}"""),
                    method = "GET",
                ).on("/collectives/7", OcsResponses.envelope("[]"), method = "PUT")

            collectives().toggleFavorite(collectiveId = 7, pageId = 9, favorite = false)

            val sent = URLDecoder.decode(
                dispatcher
                    .requestsWithMethod("PUT")
                    .single()
                    .body
                    .readUtf8(),
                "UTF-8",
            )
            assertEquals("favoritePages=[1,5]", sent)
        }

    private fun collectives() =
        CollectiveRepositoryImpl(
            api = env.api,
            circlesApi = mockk(relaxed = true),
            dao = env.db.collectiveDao(),
            pageDao = env.db.pageDao(),
            attachmentDao = env.db.attachmentDao(),
            editQueueDao = env.db.editQueueDao(),
            database = env.db,
            accountGeneration = env.accountGeneration,
        )
}
