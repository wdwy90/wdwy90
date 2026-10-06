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
       "photos":[
         {"name":"places/near/photos/a","widthPx":800,"heightPx":600,
          "authorAttributions":[{"displayName":"John Smith","uri":"//maps.google.com/maps/contrib/101563",
                                 "photoUri":"//lh3.googleusercontent.com/a-/x"}]},
         {"name":"places/near/photos/b",
          "authorAttributions":[{"displayName":"Ann Lee","uri":"https://maps.google.com/maps/contrib/7"}]},
         {"name":"places/near/photos/c","authorAttributions":[]},
         {"name":""}],
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
        assertEquals(
            listOf("places/near/photos/a", "places/near/photos/b", "places/near/photos/c"),
            joe.photos.map { it.name },
        )
        assertEquals(
            PlacePhoto("places/near/photos/a", "John Smith", "https://maps.google.com/maps/contrib/101563"),
            joe.photos[0],
        )
        assertEquals("Ann Lee", joe.photos[1].authorName)
        assertEquals("https://maps.google.com/maps/contrib/7", joe.photos[1].authorUri)
        assertNull(joe.photos[2].authorName)
        assertNull(joe.photos[2].authorUri)
        assertEquals(0, r[1].photos.size)
        assertEquals("https://joes.example", joe.websiteUri)
        assertNull(r[1].rating)
        assertEquals(11.1, joe.distanceMeters, 0.5)
    }

    @Test fun absoluteUrl() {
        assertEquals("https://maps.google.com/a", PlacesParser.absoluteUrl("//maps.google.com/a"))
        assertEquals("https://maps.google.com/a", PlacesParser.absoluteUrl("https://maps.google.com/a"))
    }

    @Test fun jsonNullsAndPhotoMapsUri() {
        val r = PlacesParser.parseNearby(
            """
            {"places":[{"id":"x","location":{"latitude":40.0,"longitude":-75.0},
              "websiteUri":null,"googleMapsUri":null,
              "photos":[
                {"name":"places/x/photos/a","authorAttributions":[{"displayName":null,"uri":null}]},
                {"name":"places/x/photos/b","googleMapsUri":"https://maps.google.com/photo/b"}]}]}
            """.trimIndent(),
            40.0, -75.0,
        )
        val p = r.single()
        assertNull(p.websiteUri)
        assertNull(p.mapsUri)
        assertNull(p.photos[0].authorName)
        assertNull(p.photos[0].authorUri)
        assertNull(p.photos[0].mapsUri)
        assertEquals("https://maps.google.com/photo/b", p.photos[1].mapsUri)
    }

    @Test fun emptyResponse() {
        assertEquals(0, PlacesParser.parseNearby("{}", 0.0, 0.0).size)
    }
}
