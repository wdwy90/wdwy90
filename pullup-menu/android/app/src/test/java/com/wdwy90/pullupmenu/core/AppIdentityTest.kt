package com.wdwy90.pullupmenu.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityTest {
    @Test fun sha1HexIsUppercaseWithoutColons() {
        assertEquals("DA39A3EE5E6B4B0D3255BFEF95601890AFD80709", AppIdentity.sha1Hex(ByteArray(0)))
        assertEquals("A9993E364706816ABA3E25717850C26C9CD0D89D", AppIdentity.sha1Hex("abc".toByteArray()))
    }
}
