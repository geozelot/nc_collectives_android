package com.megamaced.nccollectives.ui.screen.login

import com.megamaced.nccollectives.data.auth.AccountSwitcher
import com.megamaced.nccollectives.data.auth.LoginFlowException
import com.megamaced.nccollectives.data.auth.LoginFlowInitResponse
import com.megamaced.nccollectives.data.auth.NextcloudLoginFlow
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.UnknownHostException

/** U16: the login screen recovers when the user or the network doesn't cooperate. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val flow = mockk<NextcloudLoginFlow>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anUnknownHost_isSaidInWords() {
        val error = LoginFlowException("Failed to connect: Unable to resolve host", UnknownHostException("cloud.example"))

        assertEquals("Can't find a server at that address. Check it, and your connection.", loginFailureMessage(error))
    }

    @Test
    fun theSignInPage_opensOnce_andWaitingCanBeStopped() =
        runTest(dispatcher) {
            coEvery { flow.initiate(any()) } returns Result.success(initResponse())
            coEvery { flow.poll(any(), any(), any()) } coAnswers { awaitCancellation() }
            val viewModel = LoginViewModel(flow, mockk<AccountSwitcher>(relaxed = true))
            viewModel.onHostChanged("cloud.example.com")

            viewModel.startLogin()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isPolling)
            viewModel.onLoginPageOpened()
            assertNull("opened, so not re-opened on a recreation", viewModel.uiState.value.loginUrl)

            viewModel.cancelLogin()
            advanceUntilIdle()
            assertFalse("the buttons come back", viewModel.uiState.value.isPolling)
        }

    private fun initResponse(): LoginFlowInitResponse =
        Json.decodeFromString(
            """{"poll":{"token":"t","endpoint":"https://cloud.example.com/login/v2/poll"},"login":"https://cloud.example.com/login/v2/flow/t"}""",
        )
}
