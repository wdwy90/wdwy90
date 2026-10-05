package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlacesParserTest {
    private val json = """
    {"places":[
      {"id":"far","displayName":{"text":"Far Diner"},"location":{"latitude":40.0005,"longitude":-75.0}},
      {"id":"near","displayName":{"text":"Joe's Pizza","languageCode":"en"},
       "shortFormattedAddress":"1 Main St","formattedAddress":"1 Main St, Town, PA",
       "location":{"latitude":40.0001,"longitude":-75.0},"rating":4.6,
       "primaryTypeDisplayName":{"text":"Pizza Restaurant"},
       "photos":[{"name":"places/near/photos/a"},{"name":"places/near/photos/b"}],
       "websiteUri":"https://joes.example","googleMapsUri":"https://maps.google.com/?cid=1"}
    ]}
    """.trimIndent()

    @Test fun parsesAndSortsByDistance() {
        val r = PlacesParser.parseNearby(json, 40.0, -75.0)
        assertEquals(listOf("near", "far"), r.map { it.id })
        val joe = r[0]
        assertEquals("Joe's Pizza", joe.name)
        assertEquals("1 Main St", joe.address)
        assertEquals(4.6, joe.rating!!, 0.0)
        assertEquals("Pizza Restaurant", joe.category)
        assertEquals(listOf("places/near/photos/a", "places/near/photos/b"), joe.photoNames)
        assertEquals("https://joes.example", joe.websiteUri)
        assertNull(r[1].rating)
        assertEquals(11.1, joe.distanceMeters, 0.5)
    }

    @Test fun emptyResponse() {
        assertEquals(0, PlacesParser.parseNearby("{}", 0.0, 0.0).size)
    }
}
