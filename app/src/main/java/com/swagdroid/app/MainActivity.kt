package com.swagdroid.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swagdroid.app.ui.AdBlockScreen
import com.swagdroid.app.ui.AntivirusScreen
import com.swagdroid.app.ui.CleanerScreen
import com.swagdroid.app.ui.OptimizerScreen
import com.swagdroid.app.ui.PrivilegeCenterScreen
import com.swagdroid.app.ui.SoftwareFixerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SwagDroidApplication

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var selectedTabIndex by remember { mutableIntStateOf(5) }
                val tabs = listOf(
                    "1. Ядро Привилегий",
                    "2. Антивирус & MinerHunter",
                    "3. Умный Клинер",
                    "4. AdBlock (DoT / VPN / IFW)",
                    "5. Оптимизатор & Деблоатер",
                    "6. Фиксер, Телефония & Паспорт",
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    ScrollableTabRow(
                        selectedTabIndex = selectedTabIndex,
                        edgePadding = 12.dp,
                    ) {
                        tabs.forEachIndexed { index, title ->
                            Tab(
                                selected = selectedTabIndex == index,
                                onClick = { selectedTabIndex = index },
                                text = { Text(title) },
                            )
                        }
                    }

                    when (selectedTabIndex) {
                        0 -> PrivilegeCenterScreen(rollbackJournal = app.rollbackJournal)
                        1 -> AntivirusScreen(coordinator = app.antivirusCoordinator)
                        2 -> CleanerScreen(coordinator = app.cleanerCoordinator)
                        3 -> AdBlockScreen()
                        4 -> OptimizerScreen(catalogEngine = app.optimizerCatalogEngine)
                        5 -> SoftwareFixerScreen()
                    }
                }
            }
        }
    }
}
