package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinksTest {
    @Test fun subdomainsAreTheSameSite() {
        assertEquals("mcdonalds.com", WebLinks.site("www.mcdonalds.com"))
        assertTrue(WebLinks.sameSite("order.wendys.com", "www.wendys.com"))
        assertTrue(WebLinks.sameSite("WHATABURGER.com", "whataburger.com."))
    }

    @Test fun otherWebsitesAreNot() {
        assertFalse(WebLinks.sameSite("www.doordash.com", "www.bk.com"))
        assertFalse(WebLinks.sameSite("ads.example.net", "www.example.com"))
    }

    @Test fun countryDomainsKeepTheirName() {
        assertEquals("example.co.uk", WebLinks.site("menu.example.co.uk"))
        assertFalse(WebLinks.sameSite("a.co.uk", "b.co.uk"))
    }

    @Test fun unknownHostIsNeverTheSameSite() {
        assertFalse(WebLinks.sameSite(null, "www.bk.com"))
        assertFalse(WebLinks.sameSite("", ""))
    }
}
