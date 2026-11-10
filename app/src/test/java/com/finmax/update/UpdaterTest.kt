package com.finmax.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertTrue(Updater.newer("0.3.0", "0.2.0"))
        assertTrue(Updater.newer("0.10.0", "0.9.2"))
        assertTrue(Updater.newer("1.0", "0.9.9"))
        assertFalse(Updater.newer("0.2.0", "0.2.0"))
        assertFalse(Updater.newer("0.2", "0.2.1"))
    }
}
