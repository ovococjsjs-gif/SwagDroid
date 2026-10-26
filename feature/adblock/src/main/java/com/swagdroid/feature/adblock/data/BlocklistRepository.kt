package com.swagdroid.feature.adblock.data

import android.content.Context
import com.swagdroid.feature.adblock.dns.DnsPacketEngine
import com.swagdroid.feature.adblock.model.DnsQueryLogEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class PersistedCustomDomainRules(
    val blocked: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
)

@Singleton
class BlocklistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()
    private val loadedOnce = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _seedDomains = MutableStateFlow<Set<String>>(emptySet())
    private val _customBlocked = MutableStateFlow<Set<String>>(emptySet())
    private val _customAllowed = MutableStateFlow<Set<String>>(emptySet())
    private val _recentLog = MutableStateFlow<List<DnsQueryLogEntry>>(emptyList())
    private val blockedCounter = AtomicLong(0L)

    val customBlocked: StateFlow<Set<String>> = _customBlocked.asStateFlow()
    val customAllowed: StateFlow<Set<String>> = _customAllowed.asStateFlow()
    val recentLog: StateFlow<List<DnsQueryLogEntry>> = _recentLog.asStateFlow()

    val blockedCountSession: Long
        get() = blockedCounter.get()

    val totalRulesCount: Int
        get() = (_seedDomains.value.size + _customBlocked.value.size)

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loadedOnce.get()) return@withContext
        ioMutex.withLock {
            if (loadedOnce.get()) return@withLock
            val raw = context.assets.open(SEED_ASSET_NAME).bufferedReader().use { it.readText() }
            _seedDomains.value = parseDomainRulesText(raw)

            val customFile = File(context.filesDir, CUSTOM_RULES_FILENAME)
            if (customFile.exists()) {
                runCatching {
                    val saved = json.decodeFromString<PersistedCustomDomainRules>(customFile.readText())
                    _customBlocked.value = saved.blocked.mapNotNull(::normalizeDomain).toSet()
                    _customAllowed.value = saved.allowed.mapNotNull(::normalizeDomain).toSet()
                }
            }
            loadedOnce.set(true)
        }
    }

    fun isBlocked(domain: String): Boolean {
        val combinedBlocked = if (_customBlocked.value.isEmpty()) {
            _seedDomains.value
        } else {
            _seedDomains.value + _customBlocked.value
        }
        return DnsPacketEngine.shouldBlockDomain(
            domain = domain,
            blockedSet = combinedBlocked,
            allowedSet = _customAllowed.value,
        )
    }

    fun recordDnsDecision(domain: String, wasBlocked: Boolean) {
        if (wasBlocked) blockedCounter.incrementAndGet()
        val entry = DnsQueryLogEntry(
            timestampEpochMs = System.currentTimeMillis(),
            domain = domain,
            wasBlocked = wasBlocked,
        )
        _recentLog.update { prev ->
            (listOf(entry) + prev).take(80)
        }
    }

    fun addCustomRule(rawDomain: String, block: Boolean): Boolean {
        val clean = normalizeDomain(rawDomain) ?: return false
        if (block) {
            _customAllowed.update { it - clean }
            _customBlocked.update { it + clean }
        } else {
            _customBlocked.update { it - clean }
            _customAllowed.update { it + clean }
        }
        persistCustomRulesAsync()
        return true
    }

    fun removeCustomRule(domain: String) {
        val clean = domain.trim().lowercase()
        _customBlocked.update { it - clean }
        _customAllowed.update { it - clean }
        persistCustomRulesAsync()
    }

    fun buildHostsFileContent(): String {
        val effective = (_seedDomains.value + _customBlocked.value) - _customAllowed.value
        return formatSystemHostsFile(effective)
    }

    private fun persistCustomRulesAsync() {
        val snapshot = PersistedCustomDomainRules(
            blocked = _customBlocked.value,
            allowed = _customAllowed.value,
        )
        ioScope.launch {
            ioMutex.withLock {
                runCatching {
                    val target = File(context.filesDir, CUSTOM_RULES_FILENAME)
                    val tmp = File(context.filesDir, "$CUSTOM_RULES_FILENAME.tmp")
                    tmp.writeText(json.encodeToString(snapshot), Charsets.UTF_8)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
            }
        }
    }

    companion object {
        const val SEED_ASSET_NAME = "swag_blocklist_seed.txt"
        private const val CUSTOM_RULES_FILENAME = "swag_adblock_custom_rules.json"
        private val VALID_DOMAIN_REGEX = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

        fun normalizeDomain(raw: String): String? {
            var s = raw.trim().lowercase()
            if (s.startsWith("||")) s = s.removePrefix("||")
            if (s.endsWith("^")) s = s.removeSuffix("^")
            s = s.trimEnd('.')
            if (s == "localhost" || s == "broadcasthost" || s == "local") return null
            return s.takeIf { VALID_DOMAIN_REGEX.matches(it) }
        }

        fun parseDomainRulesText(rawText: String): Set<String> {
            val out = LinkedHashSet<String>()
            for (rawLine in rawText.lineSequence()) {
                val stripped = rawLine.substringBefore('#').trim()
                if (stripped.isEmpty() || stripped.startsWith("!")) continue

                val candidate = if (stripped.startsWith("0.0.0.0") || stripped.startsWith("127.0.0.1")) {
                    stripped.split(Regex("\\s+")).getOrNull(1).orEmpty()
                } else {
                    stripped
                }
                normalizeDomain(candidate)?.let { out.add(it) }
            }
            return out
        }

        fun formatSystemHostsFile(domains: Set<String>): String = buildString {
            appendLine("# Generated by SwagDroid Systemless AdBlock")
            appendLine("127.0.0.1 localhost")
            appendLine("::1 ip6-localhost")
            domains.sorted().forEach { domain ->
                append("0.0.0.0 ").appendLine(domain)
            }
        }
    }
}
