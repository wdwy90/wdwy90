package com.wdwy90.pullupmenu.core

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** What [PlacesClient] sends and how it reads errors, through a fake connection (no network). */
class PlacesClientTest {
    // Built at runtime, so no key-shaped text sits in the repository.
    private val key = "AIza" + "A".repeat(31) + "WXYZ"
    private val identity = mapOf("X-Android-Package" to "com.wdwy90.pullupmenu")
    private val opened = ArrayList<FakeConnection>()

    @Test fun photoSendsTheKeyInAHeaderNotInTheUrl() {
        assertNull(runBlocking { client(404).photo("places/p1/photos/ph1", 480) })
        val conn = opened.single()
        assertEquals("https://places.googleapis.com/v1/places/p1/photos/ph1/media?maxWidthPx=480", conn.url.toString())
        assertEquals(key, conn.getRequestProperty("X-Goog-Api-Key"))
        assertEquals("com.wdwy90.pullupmenu", conn.getRequestProperty("X-Android-Package"))
    }

    @Test fun searchSendsTheKeyInAHeaderAndTheRadius() {
        assertTrue(runBlocking { client(200, "{}").nearbyRestaurants(40.0, -75.0, 125.0) }.isEmpty())
        val conn = opened.single()
        assertEquals("https://places.googleapis.com/v1/places:searchNearby", conn.url.toString())
        assertEquals(key, conn.getRequestProperty("X-Goog-Api-Key"))
        val circle = JSONObject(conn.sent.toString()).getJSONObject("locationRestriction").getJSONObject("circle")
        assertEquals(125.0, circle.getDouble("radius"), 0.0)
    }

    @Test fun anErrorSaysWhetherTryingAgainCanHelp() {
        val busy = searchFailure(503)
        assertEquals(503, busy.code)
        assertTrue(busy.retryable)
        assertTrue(searchFailure(429).retryable) // too many requests for now
        assertFalse(searchFailure(403).retryable) // a rejected key doesn't fix itself
        assertFalse(searchFailure(400).retryable)
        assertTrue(searchFailure(403).message.orEmpty().endsWith("Places API error 403: not allowed"))
    }

    private fun searchFailure(code: Int): PlacesClient.HttpException {
        try {
            runBlocking { client(code, """{"error":{"message":"not allowed"}}""").nearbyRestaurants(40.0, -75.0, 45.0) }
        } catch (e: PlacesClient.HttpException) {
            return e
        }
        fail("no error for $code")
        throw AssertionError()
    }

    private fun client(code: Int, body: String = "") =
        PlacesClient(key, identity) { url -> FakeConnection(url, code, body).also { opened += it } }

    class FakeConnection(url: URL, private val code: Int, private val body: String) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getOutputStream(): OutputStream = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream(): InputStream? = if (code >= 400) ByteArrayInputStream(body.toByteArray()) else null
    }
}
