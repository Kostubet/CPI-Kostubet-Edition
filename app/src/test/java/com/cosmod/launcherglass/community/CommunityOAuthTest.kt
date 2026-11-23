package com.cosmod.launcherglass.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class CommunityOAuthTest {
    @Test
    fun authorizationUsesTheSupabaseOriginAndS256Challenge() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val uri = URI(CommunityOAuth.authorizeUrl(URI("https://cpi.example/"), verifier))

        assertEquals("https://cpi.example/auth/v1/authorize", uri.toString().substringBefore('?'))
        assertEquals(
            setOf(
                "provider=github",
                "redirect_to=${CommunityOAuth.REDIRECT}",
                "code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "code_challenge_method=s256",
            ),
            uri.query.split('&').toSet(),
        )
        assertTrue(!uri.toString().contains(verifier))
        val randomVerifier = CommunityOAuth.newVerifier()
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(randomVerifier))
        assertNotEquals(randomVerifier, CommunityOAuth.newVerifier())
    }

    @Test
    fun callbackAcceptsTheExactDestinationAndOneCode() {
        val code = "ca72f325-ef52-4d52-bea3-d694f024f363"
        val redirect = CommunityOAuth.REDIRECT

        assertEquals(CommunityOAuth.Callback.Code(code), CommunityOAuth.callback("$redirect?code=$code"))
        assertEquals(CommunityOAuth.Callback.Cancelled,
            CommunityOAuth.callback("$redirect?error=access_denied#error=access_denied&sb="))
        assertEquals(CommunityOAuth.Callback.Failed, CommunityOAuth.callback("$redirect?error=server_error"))
        assertNull(CommunityOAuth.callback("$redirect?code=$code&code=$code"))
        assertNull(CommunityOAuth.callback("$redirect?code=$code&error=access_denied"))
        assertNull(CommunityOAuth.callback("$redirect?access_token=$code"))
        assertNull(CommunityOAuth.callback("$redirect?code=$code#access_token=$code"))
        assertNull(CommunityOAuth.callback("$redirect?code=%2F$code"))
        assertNull(CommunityOAuth.callback("${redirect}2?code=$code"))
        assertNull(CommunityOAuth.callback("com.kostubet.cpi://other/callback?code=$code"))
        assertNull(CommunityOAuth.callback("https://community-auth/callback?code=$code"))
    }
}
