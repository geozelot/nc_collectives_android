package com.megamaced.nccollectives.data.auth

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * S9: what a failed login says, on screen and in the log. A Login Flow
 * reply carries the poll token — whoever holds it can collect the app
 * password once the user approves — and kotlinx.serialization quotes the
 * input it couldn't parse, which the error message and the debug log both
 * passed on whole.
 */
class LoginFlowSecretsTest {
    private val server = MockWebServer()
    private val logged = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(
            priority: Int,
            tag: String?,
            message: String,
            t: Throwable?,
        ) {
            logged += message
            if (t != null) logged += t.toString()
        }
    }

    @Before
    fun setUp() {
        server.start()
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        server.shutdown()
    }

    @Test
    fun anUnreadableLoginReply_isNotRepeatedOnScreenOrInTheLog() =
        runTest {
            server.enqueue(
                MockResponse().setBody("""{"poll":{"token":"secret-poll-token","endpoint":3},"login":"x"}"""),
            )

            val result = NextcloudLoginFlow().initiate(server.url("/").toString().trimEnd('/'))

            val shown = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("failed: $result", result.isFailure)
            assertTrue(shown, "secret-poll-token" !in shown)
            assertTrue(logged.joinToString("\n"), logged.none { "secret-poll-token" in it })
        }
}
