package com.megamaced.nccollectives.data.api.dto

import kotlinx.serialization.Serializable

/**
 * The part of `GET /ocs/v2.php/cloud/user` the app reads: the user id,
 * which is what WebDAV addresses a user's files by (D12). Not the login
 * name, which can be an email address or an LDAP attribute.
 */
@Serializable
data class CloudUserDto(
    val id: String,
)
