package com.swagdroid

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.swagdroid.core.designsystem.icon.SwagIcons
import com.swagdroid.core.model.SwagTab
import com.swagdroid.feature.cleaner.CleanerRoute
import com.swagdroid.feature.dashboard.DashboardRoute
import com.swagdroid.feature.fixer.FixerRoute
import com.swagdroid.feature.optimizer.OptimizerRoute
import com.swagdroid.feature.security.SecurityCenterRoute

@Composable
fun SwagAppShell() {
    var currentTab by rememberSaveable { mutableStateOf(SwagTab.DASHBOARD) }
    var vaultSheetTrigger by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                SwagTab.entries.forEach { tab ->
                    val selected = tab == currentTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.iconVector(),
                                contentDescription = tab.titleRu,
                            )
                        },
                        label = {
                            Text(
                                text = tab.titleRu,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (currentTab) {
            SwagTab.DASHBOARD -> {
                DashboardRoute(
                    onNavigateToTab = { target -> currentTab = target },
                    openVaultTrigger = vaultSheetTrigger,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            SwagTab.FIXER -> {
                FixerRoute(contentPadding = innerPadding)
            }
            SwagTab.OPTIMIZER -> {
                OptimizerRoute(contentPadding = innerPadding)
            }
            SwagTab.CLEANER -> {
                CleanerRoute(contentPadding = innerPadding)
            }
            SwagTab.SECURITY -> {
                SecurityCenterRoute(
                    contentPadding = innerPadding,
                    onOpenVault = {
                        vaultSheetTrigger++
                        currentTab = SwagTab.DASHBOARD
                    },
                )
            }
        }
    }
}

private fun SwagTab.iconVector(): ImageVector = when (this) {
    SwagTab.DASHBOARD -> SwagIcons.Pulse
    SwagTab.SECURITY -> SwagIcons.Shield
    SwagTab.CLEANER -> SwagIcons.Cleaner
    SwagTab.OPTIMIZER -> SwagIcons.Optimizer
    SwagTab.FIXER -> SwagIcons.Fixer
}
