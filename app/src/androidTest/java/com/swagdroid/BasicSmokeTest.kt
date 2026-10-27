package com.swagdroid

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Disposable emulator only. Deletion cases select exclusively UUID-scoped synthetic fixtures. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class BasicSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val fixtures = mutableListOf<File>()

    @Before fun requireDisposableEmulator() {
        assertTrue("Never run these fixtures on a user's phone", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.startsWith("generic"))
        compose.waitUntil(30_000) { exists("nav_DASHBOARD") }
    }

    @After fun removeOnlyOurFixtures() {
        if (fixtures.isNotEmpty()) {
            storageAccess(true)
            fixtures.forEach { it.delete() }
            fixtures.mapNotNull { it.parentFile }.distinct().forEach { it.delete() } // Never recursive.
        }
    }

    @Test fun allFiveScreensOpenWithoutApplyingChanges() {
        compose.onNodeWithText("SwagDroid · v1.0").assertIsDisplayed()
        for ((tab, title) in listOf("CLEANER" to "Освободить место", "FIXER" to "Доктор системы", "OPTIMIZER" to "Оптимизатор PRO", "SECURITY" to null, "DASHBOARD" to "SwagDroid · v1.0")) {
            openTab(tab)
            title?.let { compose.onNodeWithText(it).assertExists() }
            if (tab == "CLEANER") compose.onNodeWithText("Локально · Root не нужен").assertExists()
        }
    }

    @Test fun selectedTabSurvivesActivityRecreation() {
        openTab("CLEANER")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_CLEANER").assertIsSelected()
        compose.onNodeWithText("Освободить место").assertExists()
    }

    @Test fun accessibilityAuditDoesNotOfferOrPerformMassDisable() {
        val resolver = compose.activity.contentResolver
        val before = Settings.Secure.getString(resolver, "enabled_accessibility_services")
        openTab("FIXER")
        compose.onNodeWithTag("fixer_list").performScrollToNode(hasTestTag("fixer_search"))
        compose.onNodeWithTag("fixer_search").performTextReplacement("специальных")
        hideKeyboard()
        val id = "sec_accessibility_scroll_stutter_audit"
        compose.onNodeWithTag("fixer_list").performScrollToNode(hasTestTag("diagnose_$id"))
        compose.onNodeWithTag("fix_$id").assertIsNotEnabled()
        compose.onNodeWithTag("diagnose_$id").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("verdict_$id") and hasText("Нужна ручная проверка"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fix_$id").assertIsNotEnabled()
        assertEquals(before, Settings.Secure.getString(resolver, "enabled_accessibility_services"))
    }

    @Test fun a00ReturningFromPermissionSettingsRefreshesAccessWithoutAutoScan() {
        // Revoking all-files access may kill this instrumentation process. The CI host
        // establishes the denied state BEFORE am instrument, not from inside the runner.
        assertFalse("The host must start this suite without all-files access", Environment.isExternalStorageManager())
        openTab("CLEANER")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { exists("cleaner_access") }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        compose.onNodeWithTag("cleaner_access").performClick()
        // waitForIdleSync only waits for OUR process, not the Settings activity. Do not
        // send Back while the outgoing launch is still in flight (that can close MainActivity).
        compose.waitUntil(20_000) { foregroundPackage() == "com.android.settings" }
        storageAccess(true)
        compose.waitUntil(10_000) { Environment.isExternalStorageManager() }
        // Exactly one Back. Accessibility window events may lag; repeated Back based
        // on a stale Settings root can accidentally finish the returned MainActivity.
        shell("input keyevent 4")
        try {
            compose.waitUntil(20_000) {
                foregroundPackage() == InstrumentationRegistry.getInstrumentation().targetContext.packageName && exists("cleaner_scan")
            }
        } catch (error: AssertionError) {
            throw AssertionError("No scan entry after Settings: foreground=${foregroundPackage()}, activity=${compose.activityRule.scenario.state}, storage=${Environment.isExternalStorageManager()}", error)
        }
        compose.onNodeWithTag("cleaner_scan").assertIsEnabled()
        assertFalse(exists("cleanup_results"))
    }

    @Test fun cleanupPreviewRecreationAndCancelDoNotDeleteFixture() {
        val file = fixture("review-only.tmp", "synthetic file that must survive cancelled review")
        val original = file.readText()
        scan()
        compose.onNodeWithTag("cleaner_review").assertIsNotEnabled() // No automatic selection, even .tmp.
        select(file)
        review()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cleanup_plan_list").performScrollToNode(hasText(file.canonicalPath))
        compose.onNodeWithText(file.canonicalPath).assertExists()
        assertEquals(original, file.readText())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        assertEquals(original, file.readText())
        compose.onNodeWithTag("cleanup_cancel").performClick()
        compose.waitUntil(10_000) { !exists("cleanup_confirm") }
        assertEquals(original, file.readText())
    }

    @Test fun deletionTouchesOnlyTheExplicitlySelectedSyntheticFile() {
        val selected = fixture("selected-a.tmp", "delete exactly this fixture")
        val kept = fixture("kept-b.tmp", "keep this distinct fixture unchanged")
        val before = kept.readText()
        scan(); select(selected); review()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        consentAndDelete()
        compose.waitUntil(30_000) { exists("cleanup_result_files") }
        compose.onNodeWithText("Удалено файлов: 1").assertExists()
        compose.onNodeWithText("Пропущено: 0 · Ошибок: 0").assertExists()
        assertFalse(selected.exists())
        assertEquals(before, kept.readText())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Удалено файлов: 1").assertExists()
    }

    @Test fun changedFileIsSkippedAndTheResultDoesNotClaimDeletion() {
        val file = fixture("changed.tmp", "initial fixture")
        scan(); select(file); review()
        file.writeText("changed after confirmation plan was prepared - must remain")
        consentAndDelete()
        compose.waitUntil(30_000) { exists("cleanup_result_files") }
        compose.onNodeWithText("Удалено файлов: 0").assertExists()
        compose.onNodeWithText("Пропущено: 1 · Ошибок: 0").assertExists()
        assertEquals("changed after confirmation plan was prepared - must remain", file.readText())
    }

    @Test fun changingSearchDoesNotExpandOrForgetTheExplicitSelection() {
        val selected = fixture("chosen-a.tmp", "first chosen fixture")
        val kept = fixture("unselected-b.apk", "not an APK to launch, only metadata")
        scan(); select(selected)
        compose.onNodeWithTag("cleaner_list").performScrollToNode(hasTestTag("cleanup_search"))
        compose.onNodeWithTag("cleanup_search").performTextReplacement("unselected-b")
        hideKeyboard()
        compose.onNodeWithTag("cleaner_review").assertIsEnabled()
        review()
        compose.onNodeWithTag("cleanup_plan_list").performScrollToNode(hasText(selected.canonicalPath))
        compose.onNodeWithText(selected.canonicalPath).assertExists()
        assertTrue(compose.onAllNodesWithText(kept.canonicalPath).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("cleanup_cancel").performClick()
        assertTrue(selected.exists()); assertTrue(kept.exists())
    }

    private fun fixture(name: String, content: String): File {
        storageAccess(true)
        assertTrue(Environment.isExternalStorageManager())
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "swag-ci-${UUID.randomUUID()}")
        assertTrue(folder.mkdirs())
        return File(folder, name).also { it.writeText(content); fixtures += it }
    }

    private fun storageAccess(granted: Boolean) {
        assertTrue(Build.VERSION.SDK_INT >= 30)
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        shell("appops set --uid $packageName MANAGE_EXTERNAL_STORAGE ${if (granted) "allow" else "ignore"}")
    }

    private fun foregroundPackage(): String? =
        InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun scan() {
        openTab("CLEANER")
        compose.waitUntil(15_000) { exists("cleaner_scan") }
        compose.onNodeWithTag("cleaner_scan").performClick()
        compose.waitUntil(60_000) { exists("cleanup_results") }
    }

    private fun select(file: File) {
        val tag = "cleanup_select:${file.canonicalPath}"
        compose.onNodeWithTag("cleaner_list").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
    }

    private fun review() {
        compose.onNodeWithTag("cleaner_review").performClick()
        compose.waitUntil(60_000) { exists("cleanup_confirm") }
    }

    private fun consentAndDelete() {
        compose.onNodeWithTag("cleanup_ack").performClick()
        compose.onNodeWithTag("cleanup_confirm").assertIsEnabled().performClick()
    }

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    private fun hideKeyboard() = compose.runOnUiThread {
        val activity = compose.activity
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        activity.window.decorView.clearFocus()
    }
    private fun openTab(tab: String) {
        compose.onNodeWithTag("nav_$tab").performClick()
        compose.onNodeWithTag("nav_$tab").assertIsSelected()
        compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
    }
}
