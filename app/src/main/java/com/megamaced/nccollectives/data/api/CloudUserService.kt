package com.megamaced.nccollectives.data.api

import com.megamaced.nccollectives.data.api.dto.CloudUserDto
import retrofit2.http.GET

/** Nextcloud's own OCS user endpoint, for the signed-in user's id (D12). */
interface CloudUserService {
    @GET("ocs/v2.php/cloud/user")
    suspend fun currentUser(): Envelope<CloudUserDto>
}
