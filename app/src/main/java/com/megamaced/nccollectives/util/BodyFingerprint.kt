package com.megamaced.nccollectives.util

import java.security.MessageDigest

/**
 * B-99: a stable stand-in for a page body, so "is this still the body the
 * editor was opened on?" can be asked without keeping a second copy of a
 * possibly large body in `SavedStateHandle` beside the draft.
 *
 * SHA-256 rather than `String.hashCode`, because a 32-bit hash collides
 * often enough over a lifetime of edits to matter here: a collision is a
 * silent lost update.
 */
fun bodyFingerprint(body: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }
