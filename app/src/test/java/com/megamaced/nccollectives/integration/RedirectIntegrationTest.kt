package com.megamaced.nccollectives.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.megamaced.nccollectives.data.api.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-106: the authenticated client follows no redirects.
 *
 * OkHttp follows 3xx by default, and on 301/302/303 it turns a PUT into a
 * GET. A page write answered with a redirect therefore came back as the 200
 * of a GET, `webDavCall` mapped it to Success, and the queue row was deleted
 * with the text never written anywhere. Every hop is also outside
 * `HostInterceptor`'s and `AuthInterceptor`'s reach, because both are
 * application interceptors and only see the first request. A same-host
 * redirect kept Basic auth on a path the policy never approved. A cross-host
 * 307/308 re-sent the page body to the new host: OkHttp strips
 * `Authorization` there, but not the content.
 */
@RunWith(AndroidJUnit4::class)
class RedirectIntegrationTest {
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
    fun aPageWriteAnsweredWithARedirect_isNotAnsweredByTheGetItWouldBecome() =
        runTest {
            dispatcher
                .on("Page.md", redirectTo("/elsewhere/Page.md", code = 302), method = "PUT")
                .on("/elsewhere/", OcsResponses.webDav(200, etag = "\"e\"").setBody("something else"))

            val result = save()

            assertTrue("a redirect is not a save, was $result", result is ApiResult.HttpError && result.code == 302)
            assertEquals("and it isn't followed", 1, dispatcher.requests.size)
        }

    @Test
    fun aBodyIsNotResentToWhereverARedirectPoints() =
        runTest {
            dispatcher.on("Page.md", redirectTo("https://attacker.invalid/collect", code = 307), method = "PUT")

            val result = save()

            assertTrue("was $result", result is ApiResult.HttpError && result.code == 307)
            assertEquals(1, dispatcher.requests.size)
        }

    private suspend fun save() =
        env.bodyService.saveBody(
            collectivePath = IntegrationEnvironment.COLLECTIVE_PATH,
            filePath = "",
            fileName = "Page.md",
            body = "my text",
            baseEtag = "etag-1",
        )

    private fun redirectTo(
        location: String,
        code: Int,
    ) = MockResponse().setResponseCode(code).setHeader("Location", location)
}
