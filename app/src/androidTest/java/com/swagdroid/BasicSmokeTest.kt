package com.swagdroid

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** UI smoke on a disposable emulator. Never confirms cleanup or applies privileged fixes. */
@RunWith(AndroidJUnit4::class)
class BasicSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var fixture: File? = null

    @Before fun requireDisposableEmulator() {
        assertTrue(
            "These tests are for a disposable emulator, not a user's phone",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") || Build.FINGERPRINT.startsWith("generic"),
        )
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("nav_DASHBOARD").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun removeOnlyOurFixture() {
        fixture?.let {
            it.delete()
            it.parentFile?.delete() // Non-recursive: never remove files that were not created by this test.
        }
    }

    @Test fun allFiveScreensOpenWithoutApplyingChanges() {
        compose.onNodeWithText("SwagDroid · v1.0").assertIsDisplayed()
        for ((tab, title) in listOf(
            "CLEANER" to "Умная очистка",
            "FIXER" to "Доктор системы",
            "OPTIMIZER" to "Оптимизатор PRO",
            "SECURITY" to null,
            "DASHBOARD" to "SwagDroid · v1.0",
        )) {
            openTab(tab)
            if (tab == "CLEANER") {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Базовый").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(compose.onAllNodesWithText("Root · UID 0").fetchSemanticsNodes().isEmpty())
            }
            title?.let { compose.onNodeWithText(it).assertExists() }
        }
    }

    @Test fun selectedTabSurvivesActivityRecreation() {
        openTab("CLEANER")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nav_CLEANER").assertIsSelected()
        compose.onNodeWithTag("screen_CLEANER").assertIsDisplayed()
        compose.onNodeWithText("Умная очистка").assertExists()
    }

    @Test fun accessibilityAuditDoesNotOfferOrPerformMassDisable() {
        val resolver = compose.activity.contentResolver
        val before = Settings.Secure.getString(resolver, "enabled_accessibility_services")
        openTab("FIXER")
        compose.onNodeWithTag("fixer_list").performScrollToNode(hasTestTag("fixer_search"))
        compose.onNodeWithTag("fixer_search").performTextReplacement("специальных")
        compose.runOnUiThread {
            val activity = compose.activity
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
            activity.window.decorView.clearFocus()
        }
        val id = "sec_accessibility_scroll_stutter_audit"
        compose.onNodeWithTag("fixer_list").performScrollToNode(hasTestTag("diagnose_$id"))
        compose.onNodeWithTag("fix_$id").assertIsNotEnabled()
        compose.onNodeWithTag("diagnose_$id").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("verdict_$id") and hasText("Нужна ручная проверка")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("fix_$id").assertIsNotEnabled()
        assertEquals(before, Settings.Secure.getString(resolver, "enabled_accessibility_services"))
    }

    @Test fun cleanupPreviewRecreationAndCancelDoNotDeleteFixture() {
        assertTrue("All-files-access fixture needs API 30+", Build.VERSION.SDK_INT >= 30)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("appops set --uid ${compose.activity.packageName} MANAGE_EXTERNAL_STORAGE allow")
            .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
        assertTrue("Failed to grant storage access on the disposable emulator", Environment.isExternalStorageManager())
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "swag-ci-${UUID.randomUUID()}")
        assertTrue(folder.mkdirs())
        val file = File(folder, "review-only.tmp").also { it.writeText("synthetic smoke fixture - must survive cancel") }
        fixture = file
        val original = file.readText()

        openTab("CLEANER")
        compose.onNodeWithTag("cleaner_list").performScrollToNode(hasTestTag("cleaner_scan"))
        compose.onNodeWithTag("cleaner_scan").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasTestTag("cleaner_review") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("cleaner_review").performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithTag("cleanup_confirm").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("cleanup_plan_list").performScrollToNode(hasText(file.canonicalPath))
        compose.onNodeWithText(file.canonicalPath).assertExists()
        assertEquals(original, file.readText())

        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("cleanup_confirm").assertIsNotEnabled()
        assertEquals(original, file.readText())
        compose.onNodeWithTag("cleanup_cancel").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cleanup_confirm").fetchSemanticsNodes().isEmpty() }
        assertEquals(original, file.readText())
    }

    private fun openTab(tab: String) {
        compose.onNodeWithTag("nav_$tab").performClick()
        compose.onNodeWithTag("nav_$tab").assertIsSelected()
        compose.onNodeWithTag("screen_$tab").assertIsDisplayed()
    }
}
