// ISwagDroidService.aidl
package com.swagdroid.api;

/**
 * Единый Binder IPC-контракт привилегированного сервера swagdroid_server.
 * Один и тот же сервер запускается либо под ADB Shell (uid=2000), либо под Root (uid=0).
 * Все фичи приложения вызывают методы этого интерфейса и не зависят от способа запуска.
 */
interface ISwagDroidService {

    /** Возвращает JSON-сериализованный ServerInfo (uid, pid, privilegeTier, selinuxContext, apiLevel). */
    String getServerInfoJson();

    /** Список установленных пакетов для указанного пользователя (JSON-массив PackageSummary). */
    String getInstalledPackagesJson(int flags, int userId);

    /**
     * Изменяет состояние пакета (ENABLED / DISABLED_USER / SUSPENDED).
     * Защищено CriticalPackageGuard на стороне сервера.
     */
    boolean setApplicationEnabledState(String packageName, int newState, int flags, int userId);

    /** Удаляет пакет для указанного пользователя (pm uninstall --user userId) с сохранением APK в /system. */
    boolean uninstallPackageForUser(String packageName, int userId, boolean keepData);

    /** Восстанавливает ранее удалённый для пользователя системный пакет (cmd package install-existing). */
    boolean restoreExistingPackageForUser(String packageName, int userId);

    /** Читает текущий режим AppOps для пакета. */
    int getAppOpMode(int code, int uid, String packageName);

    /** Устанавливает режим AppOps (MODE_ALLOWED=0, MODE_IGNORED=1, MODE_ERRORED=2, MODE_DEFAULT=3). */
    boolean setAppOpMode(int code, int uid, String packageName, int mode);

    /** Читает значение из системной таблицы настроек (GLOBAL, SECURE, SYSTEM). */
    String getSystemSetting(String namespace, String key);

    /** Записывает значение в системную таблицу настроек (GLOBAL, SECURE, SYSTEM). */
    boolean putSystemSetting(String namespace, String key, String value);

    /** Мгновенно очищает кэши всех приложений через IPackageManager.freeStorageAndNotify / trimCaches. */
    long trimAllCaches(long freeStorageGoalBytes);

    /** Управляет белым списком энергосбережения Doze (IDeviceIdleController). */
    boolean setDeviceIdleWhitelist(String packageName, boolean whitelisted);

    /** Проверяет, находится ли пакет в белом списке Doze. */
    boolean isDeviceIdleWhitelisted(String packageName);

    /** Принудительно останавливает все процессы пакета (am force-stop). Защищено CriticalPackageGuard. */
    boolean forceStopPackage(String packageName, int userId);

    /** Замораживает/размораживает пакет через pm suspend / unsuspend. Защищено CriticalPackageGuard. */
    boolean setPackageSuspended(String packageName, boolean suspended, int userId);

    /**
     * Возвращает список активных TCP/TCP6 сокетов из /proc/net/tcp{,6} с привязкой к UID (JSON-массив ActiveSocketEntry).
     * Доступно на уровне привилегий ADB Shell (uid=2000) и Root (uid=0).
     */
    String getActiveNetworkSocketsJson();

    /** Возвращает список всех существующих идентификаторов пользователей/профилей (например, [0, 10, 999]). */
    int[] getAllUserIds();

    /**
     * Выборочная очистка только кэша конкретного пакета (pm clear --cache-only).
     * Вызывается клиентом ТОЛЬКО после успешного прохождения самопроверки CacheClearCanaryProbe!
     */
    boolean clearPackageCacheOnly(String packageName, int userId);

    /**
     * Выполняет диагностическую команду из белого списка (dumpsys, getprop, cmd package compile, sm fstrim).
     * Доступно ТОЛЬКО подписанному хост-приложению SwagDroid.
     */
    String executeWhitelistedDiagnostic(String commandKey, in String[] args);

    /** Корректно останавливает демон swagdroid_server. */
    void shutdownServer();
}
