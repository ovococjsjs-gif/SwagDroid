package com.swagdroid.feature.cleaner.path

import com.swagdroid.feature.cleaner.model.CleanerActionType
import com.swagdroid.feature.cleaner.model.StorageFinding

/**
 * Канонизатор путей и дедупликатор пересекающихся областей хранилища (`StoragePathCanonicalizer`).
 *
 * Решает две фундаментальные ошибки обычных клинеров:
 * 1. На Android пути `/sdcard/...`, `/mnt/sdcard/...`, `/storage/self/primary/...` и `/data/media/0/...`
 *    указывают на ОДИН И ТОТ ЖЕ физический файл в `/storage/emulated/0/...`. Без канонизации размер мусора
 *    завышается в 2–3 раза!
 * 2. Если приложение уже включено в очистку стандартного кэша (`CLEAR_DEFAULT_CACHE`), то вложенные папки
 *    `/storage/emulated/0/Android/data/<pkg>/cache/...` и `/data/user/0/<pkg>/cache/...` уже входят в этот объём
 *    и не должны суммироваться повторно с файловыми правилами.
 */
object StoragePathCanonicalizer {

    private val PRIMARY_EMULATED_ALIASES = listOf(
        "/sdcard/",
        "/mnt/sdcard/",
        "/storage/self/primary/",
        "/mnt/user/0/primary/",
        "/data/media/0/",
    )

    private val DATA_MEDIA_USER_REGEX = Regex("^/data/media/(\\d+)(/.*)?$")
    private val DATA_DATA_LEGACY_REGEX = Regex("^/data/data/([^/]+)(/.*)?$")

    /**
     * Приводит любой путь Android-хранилища к единому каноническому виду без завершающего слэша.
     */
    fun canonicalize(rawPath: String): String {
        var path = rawPath.trim().replace(Regex("/+"), "/")
        if (path.length > 1 && path.endsWith("/")) {
            path = path.removeSuffix("/")
        }

        if (path == "/sdcard" || path == "/mnt/sdcard" || path == "/storage/self/primary" || path == "/data/media/0") {
            return "/storage/emulated/0"
        }

        val withSlash = "$path/"
        for (alias in PRIMARY_EMULATED_ALIASES) {
            if (withSlash.startsWith(alias)) {
                val relative = withSlash.removePrefix(alias).removeSuffix("/")
                return if (relative.isEmpty()) "/storage/emulated/0" else "/storage/emulated/0/$relative"
            }
        }

        val dataMediaMatch = DATA_MEDIA_USER_REGEX.matchEntire(path)
        if (dataMediaMatch != null) {
            val userId = dataMediaMatch.groupValues[1]
            val rest = dataMediaMatch.groupValues[2]
            return "/storage/emulated/$userId$rest"
        }

        val dataDataMatch = DATA_DATA_LEGACY_REGEX.matchEntire(path)
        if (dataDataMatch != null) {
            val pkg = dataDataMatch.groupValues[1]
            val rest = dataDataMatch.groupValues[2]
            return "/data/user/0/$pkg$rest"
        }

        return path
    }

    /**
     * Удаляет дублирующиеся и вложенные друг в друга находки (`StorageFinding`),
     * чтобы ни один байт не был посчитан или удалён дважды.
     */
    fun deduplicateFindings(findings: List<StorageFinding>): List<StorageFinding> {
        if (findings.isEmpty()) return emptyList()

        // 1. Канонизируем все пути и убираем точные дубликаты
        val normalized = findings
            .map { it.copy(canonicalPath = canonicalize(it.canonicalPath)) }
            .distinctBy { it.canonicalPath }

        // 2. Собираем пакеты, у которых уже есть запись CLEAR_DEFAULT_CACHE (покрывает стандартные cache-каталоги)
        val packagesWithDefaultCacheClear = normalized
            .filter { it.action == CleanerActionType.CLEAR_DEFAULT_CACHE && !it.ownerPackage.isNullOrBlank() }
            .mapNotNull { it.ownerPackage }
            .toSet()

        // 3. Фильтруем файловые записи, которые лежат внутри стандартного cache-каталога такого пакета
        val withoutCacheOverlap = normalized.filter { finding ->
            if (finding.action == CleanerActionType.CLEAR_DEFAULT_CACHE) return@filter true
            val pkg = finding.ownerPackage
            if (pkg != null && pkg in packagesWithDefaultCacheClear) {
                val p = finding.canonicalPath
                val isInsideStandardExternalCache = p == "/storage/emulated/0/Android/data/$pkg/cache" ||
                    p.startsWith("/storage/emulated/0/Android/data/$pkg/cache/")
                val isInsideStandardInternalCache = p == "/data/user/0/$pkg/cache" ||
                    p.startsWith("/data/user/0/$pkg/cache/") ||
                    p == "/data/user/0/$pkg/code_cache" ||
                    p.startsWith("/data/user/0/$pkg/code_cache/")
                if (isInsideStandardExternalCache || isInsideStandardInternalCache) {
                    return@filter false
                }
            }
            true
        }

        // 4. Убираем дочерние пути, если родительская директория уже целиком входит в список находок
        val allPaths = withoutCacheOverlap.map { it.canonicalPath }.toSet()
        return withoutCacheOverlap.filter { candidate ->
            val path = candidate.canonicalPath
            val hasParentAlreadyInList = allPaths.any { other ->
                other != path && path.startsWith("$other/")
            }
            !hasParentAlreadyInList
        }
    }
}
