/** LOKOTUBE (C) 2026 — version comparison for the update check (offline). */
package com.lokotube.desktop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun newerVersionsWin() {
        assertTrue(Updates.isNewer("0.2.5", "0.2.4"))
        assertTrue(Updates.isNewer("v0.3.0", "0.2.9"))
        assertTrue(Updates.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updates.isNewer("1.0.0", "0.99.99"))
    }

    @Test
    fun sameOrOlderDoNot() {
        assertFalse(Updates.isNewer("0.2.4", "0.2.4"))
        assertFalse(Updates.isNewer("v0.2.3", "0.2.4"))
        assertFalse(Updates.isNewer("0.2", "0.2.0"))
    }
}
