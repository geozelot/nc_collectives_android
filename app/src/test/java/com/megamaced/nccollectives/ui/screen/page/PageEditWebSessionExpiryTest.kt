package com.megamaced.nccollectives.ui.screen.page

import androidx.lifecycle.SavedStateHandle
import com.megamaced.nccollectives.data.ServerVersionTracker
import com.megamaced.nccollectives.data.api.ApiResult
import com.megamaced.nccollectives.data.auth.StoredCredentials
import com.megamaced.nccollectives.data.auth.TokenStore
import com.megamaced.nccollectives.domain.model.Page
import com.megamaced.nccollectives.domain.repository.DirectEditingRepository
import com.megamaced.nccollectives.domain.repository.PageRepository
import com.megamaced.nccollectives.ui.navigation.Destination
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S11: when the editor's session lands on Nextcloud's login page, the app
 * asks for a fresh session rather than showing the form. Once, though: a
 * server that sends every new session to the login page as well would loop,
 * so a second expiry before the editor is ready ends in a failure the user
 * can see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditWebSessionExpiryTest {
    private val page = mockk<Page>(relaxed = true)
    private val pages = mockk<PageRepository>()
    private val directEditing = mockk<DirectEditingRepository>()
    private val versions = mockk<ServerVersionTracker>()
    private val tokenStore = mockk<TokenStore>()
    private var sessionsOpened = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { pages.getPage(PAGE_ID) } returns page
        coEvery { versions.serverVersionChanged() } returns false
        coEvery { directEditing.openSession(page) } answers { ApiResult.Success("https://cloud.example.com/s/${++sessionsOpened}") }
        every { tokenStore.getCredentials() } returns
            StoredCredentials(host = "https://cloud.example.com/nextcloud", loginName = "alice", appPassword = "x")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aFirstExpiry_opensAFreshSession() {
        val viewModel = viewModel()

        viewModel.onSessionExpired()

        assertEquals(PageEditWebUiState.Loaded("https://cloud.example.com/s/2"), viewModel.uiState.value)
    }

    @Test
    fun aSecondExpiryBeforeTheEditorIsReady_fails() {
        val viewModel = viewModel()

        viewModel.onSessionExpired()
        viewModel.onSessionExpired()

        assertTrue(viewModel.uiState.value is PageEditWebUiState.Failed)
        assertEquals("no third session", 2, sessionsOpened)
    }

    @Test
    fun anExpiryAfterTheEditorWasReady_isAFirstExpiryAgain() {
        val viewModel = viewModel()
        viewModel.onSessionExpired()
        viewModel.onEditorReady()

        viewModel.onSessionExpired()

        assertEquals(PageEditWebUiState.Loaded("https://cloud.example.com/s/3"), viewModel.uiState.value)
    }

    @Test
    fun theServersBasePathComesFromTheStoredCredential() {
        assertEquals("/nextcloud", viewModel().serverBasePath)
    }

    private fun viewModel() =
        PageEditWebViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Destination.PageEditWeb.ARG_PAGE_ID to PAGE_ID)),
            tokenStore = tokenStore,
            directEditingRepository = directEditing,
            pageRepository = pages,
            serverVersionTracker = versions,
        )

    private companion object {
        const val PAGE_ID = 17L
    }
}
