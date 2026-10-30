package com.singu.xfriend

import org.junit.Assert.*
import org.junit.Test

class DesktopImportTest {
    private val profile = ModelProfile("p", "Provider", key = "private-own-key")
    private val old = SalesData(profiles = listOf(profile))
    private val original = ResourceSnapshot(listOf(Expert("e", "Old", "old")), emptyList(), emptyList(), emptyList())
    private val bundle = DesktopBundle(listOf(Expert("e", "New", "new")), emptyList(), emptyList(), emptyList(), listOf(profile.copy(key = "incoming-key")))

    @Test fun optOutPreservesExistingKeyAndRepeatedImportDoesNotDuplicateResources() {
        var resources = original
        val next = commitDesktopBundle(old, bundle, false, resources, { resources = it }, {}, {})
        assertEquals("private-own-key", next.profiles.single().key)
        val twice = commitDesktopBundle(next, bundle, false, resources, { resources = it }, {}, {})
        assertEquals(1, resources.experts.size)
        assertEquals(1, twice.profiles.size)
        assertEquals("New", resources.experts.single().name)
    }

    @Test fun resourceWriteFailureRestoresBothSnapshots() {
        var resources = original
        var disk = old
        assertThrows(IllegalStateException::class.java) {
            commitDesktopBundle(old, bundle, true, resources, { resources = it }, { error("disk full") }, { disk = it })
        }
        assertEquals(original, resources)
        assertEquals(old, disk)
    }

    @Test fun salesWriteFailureLeavesExpertLibraryUntouched() {
        var resources = original
        var resourceWrites = 0
        assertThrows(IllegalStateException::class.java) {
            commitDesktopBundle(old, bundle, true, resources, { resources = it }, { resourceWrites++ }, { error("disk full") })
        }
        assertEquals(original, resources)
        assertEquals(0, resourceWrites)
    }
}
