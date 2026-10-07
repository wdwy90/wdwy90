package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMaskTest {
    // A fake key, built at runtime so no key-shaped text sits in the repository (secret scanners).
    private val key = "AIza" + "x1".repeat(15) + "q" + "XYZ9"

    @Test fun neverShowsTheWholeKey() {
        val m = KeyMask.mask(key)
        assertFalse(m.contains(key.dropLast(4)))
        assertTrue(m.endsWith("XYZ9"))
        assertEquals(4, m.count { it.isLetterOrDigit() })
    }

    @Test fun shortKeysShowNothing() {
        assertEquals("••••••••", KeyMask.mask("abc123"))
    }

    @Test fun blankIsNotSet() {
        assertEquals("Not set", KeyMask.mask(""))
        assertEquals("Not set", KeyMask.mask(null))
    }

    @Test fun spokenFormRevealsNoMoreThanTheMask() {
        assertEquals("hidden, ends in X Y Z 9", KeyMask.spoken(key))
        // A short key shows only dots, so nothing of it is read out either.
        assertEquals("hidden", KeyMask.spoken("abc123"))
        assertEquals("not set", KeyMask.spoken("  "))
        assertEquals("not set", KeyMask.spoken(null))
    }

    @Test fun validatesPlacesKeyShape() {
        assertEquals(39, key.length)
        assertTrue(KeyMask.looksValid(key))
        assertTrue(KeyMask.looksValid("  $key "))
        assertFalse(KeyMask.looksValid("hello"))
        assertFalse(KeyMask.looksValid(key.replace("AIza", "BIza")))
    }
}
