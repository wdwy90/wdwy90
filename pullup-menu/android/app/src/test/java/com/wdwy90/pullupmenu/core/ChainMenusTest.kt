package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ChainMenusTest {
    // Tests run with the module dir as working directory.
    private val menus = ChainMenus(File("../../shared/chain_menus.json").readText())

    @Test fun matchesCommonNameVariants() {
        assertEquals("https://www.mcdonalds.com/us/en-us/full-menu.html", menus.menuUrlFor("McDonald's"))
        assertEquals("https://www.mcdonalds.com/us/en-us/full-menu.html", menus.menuUrlFor("McDonald’s"))
        assertEquals("https://www.chick-fil-a.com/menu", menus.menuUrlFor("Chick-fil-A"))
        assertEquals("https://www.in-n-out.com/menu", menus.menuUrlFor("In-N-Out Burger"))
        assertEquals("https://www.carlsjr.com/full-menu", menus.menuUrlFor("Carl's Jr."))
        assertEquals("https://www.sonicdrivein.com/menu/", menus.menuUrlFor("Sonic Drive-In"))
        assertEquals("https://www.tacobell.com/food", menus.menuUrlFor("Taco Bell Cantina"))
        assertEquals("https://checkersandrallys.com/menu", menus.menuUrlFor("Rally's"))
    }

    @Test fun wholeWordsOnly() {
        assertNull(menus.menuUrlFor("Supersonic Car Wash"))
        assertNull(menus.menuUrlFor("Joe's Burgers"))
    }

    @Test fun normalize() {
        assertEquals("chickfila 123", ChainMenus.normalize("Chick-fil-A #123"))
    }
}
