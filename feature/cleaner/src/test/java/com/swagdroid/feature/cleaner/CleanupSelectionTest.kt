package com.swagdroid.feature.cleaner

import com.swagdroid.feature.cleaner.model.CleanerSubTab
import com.swagdroid.feature.cleaner.model.CleanupSelection
import com.swagdroid.feature.cleaner.model.CorpseFinding
import com.swagdroid.feature.cleaner.model.DuplicateCluster
import com.swagdroid.feature.cleaner.model.DuplicateMatchKind
import com.swagdroid.feature.cleaner.model.MessengerBrand
import com.swagdroid.feature.cleaner.model.MessengerShelfItem
import com.swagdroid.feature.cleaner.model.MessengerShelfKind
import com.swagdroid.feature.cleaner.model.SystemJunkCategory
import com.swagdroid.feature.cleaner.model.SystemJunkGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupSelectionTest {
    private fun media() = MessengerShelfItem("media", MessengerBrand.TELEGRAM, MessengerShelfKind.IMAGE_FEED_CACHE, 12, 1, listOf("/storage/photo.jpg"))
    private fun corpse() = CorpseFinding("corpse", "old.app", "/storage/old.app", "media", 12, 1)
    private fun duplicate(kind: DuplicateMatchKind = DuplicateMatchKind.EXACT_SHA256) =
        DuplicateCluster("duplicate", kind, "/storage/original.jpg", listOf("/storage/copy.jpg"), 12)

    @Test
    fun `media leftovers and both kinds of duplicates are unchecked by default`() {
        assertTrue(MessengerShelfKind.entries.none { it.defaultChecked })
        assertFalse(media().isSelected)
        assertFalse(corpse().isSelected)
        assertFalse(duplicate().isSelected)
        assertFalse(duplicate(DuplicateMatchKind.SIMILAR_DHASH).isSelected)
        assertTrue(CleanupSelection.from(emptyList(), listOf(media()), listOf(corpse()), listOf(duplicate())).groups.isEmpty())
    }

    @Test
    fun `selection explicitly includes every selected tab and retains its source`() {
        val cache = SystemJunkGroup(SystemJunkCategory.TEMP_AND_INCOMPLETE, 12, 1, listOf("/storage/a.tmp"), true)
        val input = CleanupSelection.from(listOf(cache), listOf(media().copy(isSelected = true)), listOf(corpse().copy(isSelected = true)), listOf(duplicate().copy(isSelected = true)))
        assertEquals(setOf(CleanerSubTab.SYSTEM_CACHE, CleanerSubTab.MESSENGERS, CleanerSubTab.CORPSES, CleanerSubTab.DUPLICATES), input.groups.map { it.source.tab }.toSet())
        assertEquals(4, input.groups.size)
        assertEquals(setOf("/storage/original.jpg"), input.protectedOriginalPaths)
        assertTrue(input.groups.single { it.source.tab == CleanerSubTab.DUPLICATES }.exactDuplicates)
    }

    @Test
    fun `unselected duplicate originals remain protected against other categories`() {
        val input = CleanupSelection.from(emptyList(), listOf(media().copy(isSelected = true)), emptyList(), listOf(duplicate()))
        assertEquals(1, input.groups.size)
        assertEquals(setOf("/storage/original.jpg"), input.protectedOriginalPaths)
    }

    @Test
    fun `disabled global trim and installer actions cannot enter a plan even with a stale selected flag`() {
        val restricted = listOf(SystemJunkCategory.PRIVILEGED_TRIM_CACHES, SystemJunkCategory.ABANDONED_STAGING_SESSIONS)
        val junk = restricted.map { SystemJunkGroup(it, 123, 1, listOf("::STAGING_SESSION::12"), isSelected = true) }
        assertTrue(restricted.none { it.defaultChecked })
        assertTrue(restricted.all { !it.cleanupBlockReason.isNullOrBlank() })
        assertTrue(CleanupSelection.from(junk, emptyList(), emptyList(), emptyList()).groups.isEmpty())
    }

    @Test
    fun `zero byte selections are not silently dropped`() {
        val group = SystemJunkGroup(SystemJunkCategory.EMPTY_FOLDERS, 0, 1, listOf("/storage/empty"), true)
        val input = CleanupSelection.from(listOf(group), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("/storage/empty"), input.groups.single().paths)
    }
}
