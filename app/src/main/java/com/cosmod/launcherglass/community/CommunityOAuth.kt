package com.cosmod.launcherglass.community

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object CommunityOAuth {
    const val REDIRECT = "com.kostubet.cpi://community-auth/callback"
    const val LIFETIME_MS = 10 * 60 * 1000L
    private val base64 = Base64.getUrlEncoder().withoutPadding()
    private val random = SecureRandom()
    private val redirect = URI(REDIRECT)
    private val codePattern = Regex("[A-Za-z0-9_-]{20,512}")

    sealed interface Callback {
        data class Code(val value: String) : Callback {
            override fun toString() = "Code"
        }
        data object Cancelled : Callback
        data object Failed : Callback
    }

    enum class Result { SIGNED_IN, CANCELLED, FAILED }

    fun newVerifier(): String = base64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun authorizeUrl(base: URI, verifier: String): String {
        val challenge = base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
        val target = URLEncoder.encode(REDIRECT, "UTF-8")
        return base.resolve("/auth/v1/authorize?provider=github&redirect_to=$target" +
            "&code_challenge=$challenge&code_challenge_method=s256").toString()
    }

    fun callback(value: String): Callback? {
        if (value.length > 4096) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != redirect.scheme || uri.rawAuthority != redirect.rawAuthority ||
            uri.rawPath != redirect.rawPath
        ) return null
        val query = uri.rawQuery?.split('&') ?: return null
        val codes = query.filter { it.startsWith("code=") }
        val errors = query.filter { it.startsWith("error=") }
        return when {
            codes.size == 1 && errors.isEmpty() && uri.rawFragment == null -> codes.single().substringAfter('=')
                .takeIf(codePattern::matches)?.let(Callback::Code)
            codes.isEmpty() && errors.size == 1 -> if (errors.single() == "error=access_denied")
                Callback.Cancelled else Callback.Failed
            else -> null
        }
    }
}
