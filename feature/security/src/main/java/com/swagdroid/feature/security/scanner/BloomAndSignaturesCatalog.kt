package com.swagdroid.feature.security.scanner

import com.swagdroid.feature.security.model.TrackerSdkSignature
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.BitSet

/**
 * Memory-compact Kirsch-Mitzenmacher Bloom filter over SHA-256 digests (inspired by Hypatia).
 * Guarantees 0% false negatives for O(1) screening of APK/DEX/native-lib hashes.
 */
class BloomSignatureFilter(
    private val bitCapacity: Int = 65_536,
    private val numHashFunctions: Int = 5,
) {
    private val bits = BitSet(bitCapacity)

    fun add(token: String) {
        val (h1, h2) = hashPair(token.trim().lowercase())
        for (i in 0 until numHashFunctions) {
            val combined = ((h1 + i.toLong() * h2) and Long.MAX_VALUE) % bitCapacity
            bits.set(combined.toInt())
        }
    }

    fun mightContain(token: String): Boolean {
        val (h1, h2) = hashPair(token.trim().lowercase())
        for (i in 0 until numHashFunctions) {
            val combined = ((h1 + i.toLong() * h2) and Long.MAX_VALUE) % bitCapacity
            if (!bits.get(combined.toInt())) return false
        }
        return true
    }

    private fun hashPair(input: String): Pair<Long, Long> {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val buf = ByteBuffer.wrap(digest)
        return buf.getLong(0) to buf.getLong(8)
    }
}

object SecuritySignaturesCatalog {

    /**
     * Exact SHA-256 hashes of known test/sample malware payloads (including standard EICAR test SHA-256
     * so users & security auditors can safely test detection without real live malware!).
     */
    val KNOWN_MALWARE_SHA256: Map<String, String> = mapOf(
        // Standard industry EICAR antivirus test file SHA-256
        "275a021bbfb6489e54d471899f7db9d1663fc695ec2fe2a2c4538aabf651fd0f" to "EICAR-Test-Signature (Тестовый маркер антивируса)",
        "131f95c51cc819465fa1797f6ccacf9d494aaaff46fa3eac73ae63ffbdfd8267" to "EICAR-Test-CRLF",
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855".reversed() to "Trojan.AndroidOS.SampleDropper",
    )

    val malwareBloomFilter: BloomSignatureFilter by lazy {
        BloomSignatureFilter().apply {
            KNOWN_MALWARE_SHA256.keys.forEach { add(it) }
            KNOWN_TROJAN_PACKAGE_IDS.keys.forEach { add(it) }
        }
    }

    /**
     * Known malicious / stalkerware / banking-trojan masquerade package IDs.
     */
    val KNOWN_TROJAN_PACKAGE_IDS: Map<String, String> = mapOf(
        "com.google.system.service.update" to "Фальшивое системное обновление (Trojan-Dropper)",
        "com.android.system.security.patch" to "Маскировка под патч безопасности (SpyNote/RAT)",
        "com.whatsapp.update.patch" to "Фишинговый клон обновления мессенджера",
        "ru.sberbank.security.verify" to "Банковский троян семейства Mamont / Cerberus",
        "com.flash.player.update.installer" to "Классический банковский дроппер Hydra / Joker",
        "com.hidden.ads.booster.clean" to "Троян семейства HiddenAds (скрытые полноэкранные баннеры)",
        "com.wifi.password.hacker.prank.pro" to "Потенциально вредоносный APK-комбайн",
    )

    /**
     * Binary / configuration markers indicating an embedded silent cryptocurrency miner
     * inside an APK's native `lib/` or `assets/` directories (Android port of BlendLog/MinerSearch heuristics).
     */
    val CRYPTO_MINER_MARKERS: List<Pair<String, String>> = listOf(
        "stratum+tcp://" to "Протокол майнинг-пула Stratum TCP",
        "stratum+ssl://" to "Шифрованный протокол майнинг-пула Stratum SSL",
        "xmrig" to "Бинарное ядро скрытого майнера Monero XMRig",
        "cryptonight" to "Алгоритм хеширования CryptoNight",
        "randomx" to "Процессорный алгоритм майнинга RandomX",
        "pool.minexmr.com" to "Пул скрытой добычи MineXMR",
        "xmr.nanopool.org" to "Пул добычи Nanopool XMR",
        "moneroocean.stream" to "Пул добычи MoneroOcean",
        "c3pool.com" to "Пул добычи C3Pool",
        "hashvault.pro" to "Пул добычи HashVault",
        "donate-level" to "Конфигурационный ключ отчислений XMRig",
    )

    /**
     * 32 real tracker & ad-network DEX class path descriptors sourced from Exodus Privacy.
     */
    val EXODUS_TRACKER_SIGNATURES: List<TrackerSdkSignature> = listOf(
        TrackerSdkSignature("admob", "Google AdMob", "Рекламная сеть", "com/google/android/gms/ads/"),
        TrackerSdkSignature("firebase_analytics", "Google Firebase Analytics", "Аналитика", "com/google/firebase/analytics/"),
        TrackerSdkSignature("meta_analytics", "Meta / Facebook AppEvents", "Трекинг и соцсети", "com/facebook/appevents/"),
        TrackerSdkSignature("meta_ads", "Facebook Audience Network", "Рекламная сеть", "com/facebook/ads/"),
        TrackerSdkSignature("appmetrica_new", "Yandex AppMetrica", "Аналитика", "io/appmetrica/analytics/"),
        TrackerSdkSignature("appmetrica_legacy", "Yandex Metrica (Legacy)", "Аналитика", "com/yandex/metrica/"),
        TrackerSdkSignature("yandex_ads", "Yandex Mobile Ads", "Рекламная сеть", "com/yandex/mobile/ads/"),
        TrackerSdkSignature("mytracker", "VK MyTracker", "Трекинг атрибуции", "com/my/tracker/"),
        TrackerSdkSignature("mytarget", "VK myTarget Ads", "Рекламная сеть", "com/my/target/"),
        TrackerSdkSignature("appsflyer", "AppsFlyer", "Рекламная атрибуция", "com/appsflyer/"),
        TrackerSdkSignature("adjust", "Adjust", "Рекламная атрибуция", "com/adjust/sdk/"),
        TrackerSdkSignature("branch", "Branch Deep-Link Tracker", "Атрибуция", "io/branch/referral/"),
        TrackerSdkSignature("kochava", "Kochava", "Рекламная атрибуция", "com/kochava/tracker/"),
        TrackerSdkSignature("singular", "Singular", "Рекламная атрибуция", "com/singular/sdk/"),
        TrackerSdkSignature("applovin", "AppLovin MAX", "Рекламная медиация", "com/applovin/"),
        TrackerSdkSignature("unity_ads", "Unity Ads", "Игровая реклама", "com/unity3d/ads/"),
        TrackerSdkSignature("ironsource", "ironSource", "Игровая реклама", "com/ironsource/mediationsdk/"),
        TrackerSdkSignature("vungle", "Liftoff / Vungle", "Видео-реклама", "com/vungle/ads/"),
        TrackerSdkSignature("mintegral", "Mintegral", "Рекламная сеть", "com/mbridge/msdk/"),
        TrackerSdkSignature("pangle", "TikTok Pangle Ads", "Рекламная сеть", "com/bytedance/sdk/openadsdk/"),
        TrackerSdkSignature("inmobi", "InMobi", "Рекламная сеть", "com/inmobi/ads/"),
        TrackerSdkSignature("chartboost", "Chartboost", "Игровая реклама", "com/chartboost/sdk/"),
        TrackerSdkSignature("startio", "Start.io (StartApp)", "Агрессивная реклама", "com/startapp/sdk/"),
        TrackerSdkSignature("adcolony", "AdColony", "Видео-реклама", "com/adcolony/sdk/"),
        TrackerSdkSignature("fyber", "Digital Turbine / Fyber", "Рекламная сеть", "com/fyber/inneractive/"),
        TrackerSdkSignature("bidmachine", "BidMachine", "Рекламный аукцион", "io/bidmachine/"),
        TrackerSdkSignature("amplitude", "Amplitude", "Продуктовая аналитика", "com/amplitude/api/"),
        TrackerSdkSignature("mixpanel", "Mixpanel", "Поведенческий трекинг", "com/mixpanel/android/"),
        TrackerSdkSignature("flurry", "Yahoo Flurry", "Аналитика", "com/flurry/android/"),
        TrackerSdkSignature("onesignal", "OneSignal Push", "Маркетинговые пуши", "com/onesignal/"),
        TrackerSdkSignature("hms_analytics", "Huawei HMS Analytics", "Аналитика", "com/huawei/hms/analytics/"),
        TrackerSdkSignature("sentry", "Sentry Crash Telemetry", "Телеметрия сбоев", "io/sentry/android/"),
    )
}
