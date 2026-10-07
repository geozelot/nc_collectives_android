package com.megamaced.nccollectives.ui.screen.favorites

import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.domain.model.Collective
import com.megamaced.nccollectives.domain.repository.CollectiveRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Opening Favorites refreshed every collective's page list — two requests
 * each, one collective after another — when only the collectives holding a
 * favourite can contribute a row. An account in thirty collectives with
 * favourites in two paid for sixty requests every time it opened the
 * screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRefreshTest {
    private val collectives = mockk<CollectiveRepository>()
    private val pages = mockk<PageRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { collectives.refresh() } returns ApiResult.Success(Unit)
        every { collectives.observeCollectives() } returns
            flowOf(listOf(collective(1, favorites = setOf(10)), collective(2), collective(3, favorites = setOf(30))))
        coEvery { pages.refresh(any()) } returns ApiResult.Success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun onlyCollectivesWithFavoritesAreRefreshed() {
        FavoritesViewModel(collectives, pages)

        coVerify(exactly = 1) { pages.refresh(1) }
        coVerify(exactly = 1) { pages.refresh(3) }
        coVerify(exactly = 0) { pages.refresh(2) }
    }

    private fun collective(
        id: Long,
        favorites: Set<Long> = emptySet(),
    ) = Collective(
        id = id,
        name = "C$id",
        slug = null,
        emoji = null,
        circleId = null,
        canEdit = true,
        canShare = true,
        level = 0,
        userShowMembers = false,
        isPageShare = false,
        trashed = false,
        favoritePageIds = favorites,
    )
}
