package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.*
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.*
import dev.brahmkshatriya.echo.extension.SoundCloudExtension
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SoundCloudLoginTest {
    private class Memory : Settings {
        val strings = mutableMapOf<String, String>()
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String?) { if (value == null) strings.remove(key) else strings[key] = value }
        override fun getBoolean(key: String): Boolean? = null
        override fun getInt(key: String): Int? = null
        override fun getStringSet(key: String): Set<String>? = null
        override fun putBoolean(key: String, value: Boolean?) = Unit
        override fun putInt(key: String, value: Int?) = Unit
        override fun putStringSet(key: String, value: Set<String>?) = Unit
    }
    private val profile = """{"id":42,"username":"Fixture","avatar_url":"https://example.invalid/avatar"}"""
    private fun client(response: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = response(chain.request())
        Response.Builder().request(chain.request()).code(code).protocol(Protocol.HTTP_1_1).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun extension(prefs: Settings = Memory(), response: (Request) -> Pair<Int, String>) = SoundCloudExtension(client(response)).also { it.setSettings(prefs) }

    @Test fun browserReturnsOnlyAnAccountVerifiedByTheService() = runBlocking {
        val prefs = Memory()
        val c = extension(prefs) { request ->
            assertEquals("OAuth fixture", request.header("Authorization"))
            assertNull(prefs.getString("auth_token"))
            200 to profile
        }
        val users = c.webViewRequest.onStop(NetworkRequest("https://soundcloud.com/fixture"), "oauth_token=fixture")!!
        assertEquals("42", users.single().id); assertEquals("Fixture", users.single().name)
        assertEquals("fixture", prefs.getString("auth_token"))
    }
    @Test fun legacyAuthCookieAndEncodedOAuthPrefixRemainSupported() = runBlocking {
        for (cookie in listOf("auth_token=fixture", "oauth_token=OAuth%20fixture", "oauth_token=; auth_token=fixture")) {
            val c = extension { request -> assertEquals("OAuth fixture", request.header("Authorization")); 200 to profile }
            assertEquals("42", c.webViewRequest.onStop(NetworkRequest("https://soundcloud.com/"), cookie)!!.single().id)
        }
    }
    @Test fun missingCookiesDoNotInventAUser() = runBlocking {
        val c = extension { error("No request expected") }
        assertNull(c.webViewRequest.onStop(NetworkRequest("https://soundcloud.com/"), "anonymous=fixture"))
    }
    @Test fun rejectedTokenCannotReplaceAnExistingAccount() = runBlocking {
        val prefs = Memory(); prefs.putString("auth_token", "old")
        val c = extension(prefs) { 401 to "{}" }
        c.setLoginUser(User("old-id", "Old", extras = mapOf("auth_token" to "old")))
        assertTrue(runCatching { c.onLogin("token", mapOf("oauth_token" to "invalid")) }.exceptionOrNull() is ClientException.LoginRequired)
        assertEquals("old", prefs.getString("auth_token")); assertEquals("old-id", c.getCurrentUser()!!.id)
    }
    @Test fun publicMeIsTriedIfTheWebEndpointRejectsTheSession() = runBlocking {
        val hosts = mutableListOf<String>()
        val c = extension { request -> hosts += request.url.host; if (hosts.size == 1) 403 to "{}" else 200 to profile }
        assertEquals("42", c.onLogin("token", mapOf("oauth_token" to "fixture")).single().id)
        assertEquals(listOf("api-v2.soundcloud.com", "api.soundcloud.com"), hosts)
    }
    @Test fun errorJsonWithoutAProfileNeverCreatesSoundCloudUserPlaceholder() = runBlocking {
        val prefs = Memory(); val c = extension(prefs) { 200 to """{"errors":[{"message":"fixture"}]}""" }
        assertTrue(runCatching { c.onLogin("token", mapOf("oauth_token" to "fixture")) }.isFailure)
        assertNull(prefs.getString("auth_token")); assertNull(c.getCurrentUser())
    }
    @Test fun rateLimitsAreReportedWithoutTryingAnotherEndpointImmediately() = runBlocking {
        var calls = 0; val c = extension { calls++; 429 to "{}" }
        assertTrue(runCatching { c.onLogin("token", mapOf("oauth_token" to "fixture")) }.isFailure)
        assertEquals(1, calls)
    }
    @Test fun manualInputStillAcceptsRawTokensAndCookieValues() = runBlocking {
        for (value in listOf("fixture", "OAuth fixture", "oauth_token=fixture; path=/", "auth_token=fixture")) {
            val c = extension { request -> assertEquals("OAuth fixture", request.header("Authorization")); 200 to profile }
            assertEquals("42", c.onLogin("token", mapOf("oauth_token" to value)).single().id)
        }
    }
    @Test fun blankAndMalformedManualInputsNeverReachTheService() = runBlocking {
        val c = extension { error("No request expected") }
        for (value in listOf("", " ", "OAuth ", "Bearer ", "oauth_token=", "{\"token\":\"fixture\"}", "fixture\r\nother")) {
            assertTrue(runCatching { c.onLogin("token", mapOf("oauth_token" to value)) }.isFailure)
        }
    }
    @Test fun browserClientIdIsValidatedAndPersistedWithTheAccount() = runBlocking {
        val prefs = Memory()
        val c = extension(prefs) { request ->
            assertEquals("fixtureClient123456789", request.url.queryParameter("client_id"))
            200 to profile
        }
        c.webViewRequest.onStop(NetworkRequest("https://soundcloud.com/", mapOf("X-Echo-SoundCloud-Client-Id" to "fixtureClient123456789")), "oauth_token=fixture")
        assertEquals("fixtureClient123456789", prefs.getString("client_id"))
    }
    @Test fun mobileSessionIsValidatedOnTheMobileProfileEndpoint() = runBlocking {
        val prefs = Memory()
        val c = extension(prefs) { request ->
            assertEquals("api-mobi.soundcloud.com", request.url.host)
            assertEquals("OAuth fixture", request.header("Authorization"))
            200 to profile
        }
        val users = c.webViewRequest.onStop(NetworkRequest("https://m.soundcloud.com/"), "oauth_token=fixture")!!
        assertEquals("42", users.single().id)
        assertEquals("fixture", prefs.getString("auth_token"))
    }
    @Test fun mobileRejectionCanFallBackToTheExistingWebEndpoint() = runBlocking {
        for (status in listOf(403, 404, 405)) {
            val hosts = mutableListOf<String>()
            val c = extension { request -> hosts += request.url.host; if (hosts.size == 1) status to "{}" else 200 to profile }
            assertEquals("42", c.webViewRequest.onStop(NetworkRequest("https://m.soundcloud.com/"), "oauth_token=fixture")!!.single().id)
            assertEquals(listOf("api-mobi.soundcloud.com", "api-v2.soundcloud.com"), hosts)
        }
    }
    @Test fun browserRequestsTheSoundCloudCaptureProfile() {
        val c = extension { error("No request expected") }
        assertEquals("soundcloud-login-v1", c.webViewRequest.initialUrl.headers["X-Echo-WebView-Profile"])
        assertTrue(c.webViewRequest.maxTimeout >= 600_000)
    }
    @Test fun logoutDoesNotRestoreCachedTokenAsAnActiveAccount() = runBlocking {
        val prefs = Memory(); prefs.putString("auth_token", "old")
        val c = extension(prefs) { error("No request expected") }
        c.setLoginUser(User("old-id", "Old", extras = mapOf("auth_token" to "old")))
        c.setLoginUser(null)
        assertNull(c.getCurrentUser()); assertEquals("old", prefs.getString("auth_token"))
    }
}
