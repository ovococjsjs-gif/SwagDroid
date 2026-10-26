package com.swagdroid.app

import android.app.Application
import com.swagdroid.backend.root.PrivilegeManager
import com.swagdroid.core.rollback.RollbackJournal
import com.swagdroid.feature.antivirus.AntivirusCoordinator
import com.swagdroid.feature.antivirus.layer4.QuarantineManager
import com.swagdroid.feature.cleaner.CleanerCoordinator
import com.swagdroid.feature.optimizer.OptimizerCatalogEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class SwagDroidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var rollbackJournal: RollbackJournal
        private set

    lateinit var antivirusCoordinator: AntivirusCoordinator
        private set

    lateinit var cleanerCoordinator: CleanerCoordinator
        private set

    lateinit var optimizerCatalogEngine: OptimizerCatalogEngine
        private set

    override fun onCreate() {
        super.onCreate()
        rollbackJournal = RollbackJournal(File(filesDir, "rollback_journal"))
        val quarantineManager = QuarantineManager(
            vaultDir = File(filesDir, "quarantine_vault"),
            rollbackJournal = rollbackJournal,
        )
        antivirusCoordinator = AntivirusCoordinator(
            quarantineManager = quarantineManager,
        )
        cleanerCoordinator = CleanerCoordinator(
            hostFilesDir = filesDir,
            hostCacheDir = cacheDir,
        )
        optimizerCatalogEngine = OptimizerCatalogEngine(
            rollbackJournal = rollbackJournal,
        )
        appScope.launch {
            rollbackJournal.loadFromDisk()
        }
        // Автоматическая попытка поднять привилегированный сервер при старте приложения
        PrivilegeManager.autoEscalatePrivileges(this)
    }
}
