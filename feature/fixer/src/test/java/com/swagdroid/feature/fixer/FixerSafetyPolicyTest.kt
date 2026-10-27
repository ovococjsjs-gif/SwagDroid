package com.swagdroid.feature.fixer

import com.swagdroid.feature.fixer.catalog.FixerCatalog
import com.swagdroid.feature.fixer.safety.FixerSafetyPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixerSafetyPolicyTest {
    @Test
    fun `mass package removal is blocked with a reason`() {
        assertTrue(!FixerSafetyPolicy.automaticFixBlockReason("pkg_ghost_uninstalled_conflict").isNullOrBlank())
    }

    @Test
    fun `accessibility shutdown is blocked with a reason`() {
        assertTrue(!FixerSafetyPolicy.automaticFixBlockReason("sec_accessibility_scroll_stutter_audit").isNullOrBlank())
    }

    @Test
    fun `installer sessions cannot be abandoned wholesale`() {
        assertTrue(!FixerSafetyPolicy.automaticFixBlockReason("stor_abandoned_install_sessions").isNullOrBlank())
    }

    @Test
    fun `blocked scenarios remain discoverable and no longer advertise a reversible fix`() {
        for (id in listOf(FixerSafetyPolicy.PACKAGE_REMNANTS, FixerSafetyPolicy.INSTALL_SESSIONS, FixerSafetyPolicy.ACCESSIBILITY_SERVICES, FixerSafetyPolicy.USER_CERTIFICATES)) {
            val playbook = FixerCatalog.byId(id)
            assertNotNull("Missing manual-check scenario: $id", playbook)
            assertFalse(playbook!!.supportsVaultRollback)
            assertTrue(playbook.fixActionSummary.isNotBlank())
        }
    }

    @Test
    fun `restriction does not disable unrelated fixes`() {
        assertNull(FixerSafetyPolicy.automaticFixBlockReason("net_captive_portal_mirrors"))
    }
}
