package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET

/**
 * Single read against the GitHub Releases API to back the in-app update
 * check. Runs against `api.github.com` on a separate OkHttp/Retrofit pair
 * (see [com.megamaced.nccollectives.di.NetworkModule.provideGitHubReleaseService])
 * so the call doesn't carry the user's Nextcloud Basic-auth header from
 * [AuthInterceptor] or the host rewrite from [HostInterceptor].
 */
interface GitHubReleaseService {
    // The repository is the build's own (BuildConfig.RELEASE_REPO), so a
    // build from a fork checks the fork's releases, not this one's.
    @GET("repos/" + BuildConfig.RELEASE_REPO + "/releases/latest")
    suspend fun latestRelease(): GitHubReleaseDto
}

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("prerelease") val preRelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
)
