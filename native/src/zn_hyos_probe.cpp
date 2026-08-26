#include "zygisk_next_api.h"

#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char *kTag = "MiWeatherLocationNative";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kSpawnerPath = "/system_ext/bin/hyos_spawner";
constexpr const char *kPosId = "23.106_113.325";
constexpr const char *kName = "广州塔";
constexpr const char *kStreetName = "阅江西路";
constexpr const char *kLongitude = "113.324521";
constexpr const char *kLatitude = "23.106428";
constexpr const char *kBelongings = "广州市, 广东, 中国";
constexpr const char *kExtra = "weathercn:101280108";
constexpr const char *kLocale = "zh_cn";

std::atomic<bool> gTargetChild{false};
std::atomic<bool> gWorkerStarted{false};

using DlopenFn = void *(*)(const char *, int);
using AndroidDlopenExtFn = void *(*)(const char *, int, const android_dlextinfo *);
DlopenFn gOriginalDlopen = nullptr;
AndroidDlopenExtFn gOriginalAndroidDlopenExt = nullptr;

void fileLog(const char *message) {
    static constexpr const char *paths[] = {
            "/data/user_de/0/com.miui.weather2/cache/miweatherlocation_native.log",
            "/data/user/0/com.miui.weather2/cache/miweatherlocation_native.log",
            "/data/data/com.miui.weather2/cache/miweatherlocation_native.log",
    };
    for (const char *path : paths) {
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd < 0) continue;
        size_t len = strnlen(message == nullptr ? "" : message, 4095);
        (void)write(fd, message == nullptr ? "" : message, len);
        (void)write(fd, "\n", 1);
        close(fd);
        return;
    }
}

void logLine(int priority, const char *fmt, ...) {
    char buffer[2048]{};
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_write(priority, kTag, buffer);
    if (gTargetChild.load(std::memory_order_relaxed)) fileLog(buffer);
}

struct FindLibraryContext {
    std::vector<std::string> needles;
    std::string path;
};

int findLibraryCallback(dl_phdr_info *info, size_t, void *data) {
    auto *ctx = static_cast<FindLibraryContext *>(data);
    if (info == nullptr || info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    std::string path(info->dlpi_name);
    for (const auto &needle : ctx->needles) {
        if (path.find(needle) != std::string::npos) {
            ctx->path = path;
            return 1;
        }
    }
    return 0;
}

std::string findLoadedLibrary(std::initializer_list<const char *> names) {
    FindLibraryContext ctx;
    for (const char *name : names) ctx.needles.emplace_back(name);
    dl_iterate_phdr(findLibraryCallback, &ctx);
    return ctx.path;
}

struct sqlite3;
struct sqlite3_stmt;

struct SqliteApi {
    using OpenV2 = int (*)(const char *, sqlite3 **, int, const char *);
    using Close = int (*)(sqlite3 *);
    using Exec = int (*)(sqlite3 *, const char *, int (*)(void *, int, char **, char **), void *, char **);
    using ErrMsg = const char *(*)(sqlite3 *);
    using PrepareV2 = int (*)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
    using Step = int (*)(sqlite3_stmt *);
    using Finalize = int (*)(sqlite3_stmt *);
    using BindText = int (*)(sqlite3_stmt *, int, const char *, int, void (*)(void *));
    using ColumnInt = int (*)(sqlite3_stmt *, int);
    using BusyTimeout = int (*)(sqlite3 *, int);

    void *handle = nullptr;
    OpenV2 openV2 = nullptr;
    Close close = nullptr;
    Exec exec = nullptr;
    ErrMsg errMsg = nullptr;
    PrepareV2 prepareV2 = nullptr;
    Step step = nullptr;
    Finalize finalize = nullptr;
    BindText bindText = nullptr;
    ColumnInt columnInt = nullptr;
    BusyTimeout busyTimeout = nullptr;

    bool ready() const {
        return handle != nullptr && openV2 && close && exec && errMsg && prepareV2 && step &&
               finalize && bindText && columnInt;
    }
};

void *resolveSymbol(void *handle, const char *name) {
    void *symbol = dlsym(handle, name);
    if (symbol == nullptr && handle != RTLD_DEFAULT) symbol = dlsym(RTLD_DEFAULT, name);
    return symbol;
}

SqliteApi loadSqliteApi() {
    SqliteApi api;
    std::string path = findLoadedLibrary({"libsqlite3.so", "libmisqlite3.so"});
    if (!path.empty()) {
        api.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_NOLOAD);
        if (api.handle == nullptr) api.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    }
    if (api.handle == nullptr) api.handle = dlopen("libsqlite3.so", RTLD_NOW | RTLD_NOLOAD);
    if (api.handle == nullptr) return api;

    api.openV2 = reinterpret_cast<SqliteApi::OpenV2>(resolveSymbol(api.handle, "sqlite3_open_v2"));
    api.close = reinterpret_cast<SqliteApi::Close>(resolveSymbol(api.handle, "sqlite3_close"));
    api.exec = reinterpret_cast<SqliteApi::Exec>(resolveSymbol(api.handle, "sqlite3_exec"));
    api.errMsg = reinterpret_cast<SqliteApi::ErrMsg>(resolveSymbol(api.handle, "sqlite3_errmsg"));
    api.prepareV2 = reinterpret_cast<SqliteApi::PrepareV2>(resolveSymbol(api.handle, "sqlite3_prepare_v2"));
    api.step = reinterpret_cast<SqliteApi::Step>(resolveSymbol(api.handle, "sqlite3_step"));
    api.finalize = reinterpret_cast<SqliteApi::Finalize>(resolveSymbol(api.handle, "sqlite3_finalize"));
    api.bindText = reinterpret_cast<SqliteApi::BindText>(resolveSymbol(api.handle, "sqlite3_bind_text"));
    api.columnInt = reinterpret_cast<SqliteApi::ColumnInt>(resolveSymbol(api.handle, "sqlite3_column_int"));
    api.busyTimeout = reinterpret_cast<SqliteApi::BusyTimeout>(resolveSymbol(api.handle, "sqlite3_busy_timeout"));
    return api;
}

constexpr int SQLITE_OK = 0;
constexpr int SQLITE_ROW = 100;
constexpr int SQLITE_DONE = 101;
constexpr int SQLITE_OPEN_READWRITE = 0x00000002;
constexpr int SQLITE_OPEN_FULLMUTEX = 0x00010000;

bool bindText(const SqliteApi &api, sqlite3_stmt *stmt, int index, const char *value) {
    auto transient = reinterpret_cast<void (*)(void *)>(-1);
    return api.bindText(stmt, index, value, -1, transient) == SQLITE_OK;
}

int execSql(const SqliteApi &api, sqlite3 *db, const char *sql) {
    int rc = api.exec(db, sql, nullptr, nullptr, nullptr);
    if (rc != SQLITE_OK) {
        logLine(ANDROID_LOG_ERROR, "sqlite exec rc=%d sql=%s err=%s", rc, sql,
                api.errMsg ? api.errMsg(db) : "unknown");
    }
    return rc;
}

bool tableExists(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='selectedcity' LIMIT 1";
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return false;
    int rc = api.step(stmt);
    api.finalize(stmt);
    return rc == SQLITE_ROW;
}

int favoritePosition(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "SELECT position FROM selectedcity WHERE posID=? AND flag=0 LIMIT 1";
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return -1;
    if (!bindText(api, stmt, 1, kPosId)) {
        api.finalize(stmt);
        return -1;
    }
    int rc = api.step(stmt);
    int position = rc == SQLITE_ROW ? api.columnInt(stmt, 0) : -1;
    api.finalize(stmt);
    return position;
}

bool insertFavorite(const SqliteApi &api, sqlite3 *db) {
    const char *sql =
            "INSERT INTO selectedcity "
            "(posID,flag,position,name,street_name,longtitude,latitude,belongings,extra,locale) "
            "VALUES (?,0,1,?,?,?,?,?,?,?)";
    sqlite3_stmt *stmt = nullptr;
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return false;

    bool ok = bindText(api, stmt, 1, kPosId) && bindText(api, stmt, 2, kName) &&
              bindText(api, stmt, 3, kStreetName) && bindText(api, stmt, 4, kLongitude) &&
              bindText(api, stmt, 5, kLatitude) && bindText(api, stmt, 6, kBelongings) &&
              bindText(api, stmt, 7, kExtra) && bindText(api, stmt, 8, kLocale);

    int rc = ok ? api.step(stmt) : -1;
    api.finalize(stmt);
    if (rc != SQLITE_DONE) {
        logLine(ANDROID_LOG_ERROR, "favorite insert rc=%d err=%s", rc,
                api.errMsg ? api.errMsg(db) : "unknown");
        return false;
    }
    return true;
}

std::string findWeatherDatabase() {
    static constexpr const char *candidates[] = {
            "/data/user_de/0/com.miui.weather2/databases/weather.db",
            "/data/user/0/com.miui.weather2/databases/weather.db",
            "/data/data/com.miui.weather2/databases/weather.db",
    };
    for (const char *path : candidates) {
        if (access(path, R_OK | W_OK) == 0) return path;
    }
    return {};
}

bool ensureFavorite(const SqliteApi &api, const std::string &dbPath) {
    sqlite3 *db = nullptr;
    int rc = api.openV2(dbPath.c_str(), &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr);
    if (rc != SQLITE_OK || db == nullptr) {
        logLine(ANDROID_LOG_WARN, "open weather.db failed rc=%d path=%s", rc, dbPath.c_str());
        if (db != nullptr) api.close(db);
        return false;
    }

    if (api.busyTimeout) api.busyTimeout(db, 3000);
    if (!tableExists(api, db)) {
        logLine(ANDROID_LOG_WARN, "selectedcity table not ready yet");
        api.close(db);
        return false;
    }

    int existing = favoritePosition(api, db);
    if (existing >= 0) {
        logLine(ANDROID_LOG_INFO, "广州塔 already exists position=%d; real location untouched", existing);
        api.close(db);
        return true;
    }

    bool success = false;
    if (execSql(api, db, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, db,
                    "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK &&
            insertFavorite(api, db) && execSql(api, db, "COMMIT") == SQLITE_OK) {
            success = favoritePosition(api, db) == 1;
        }
        if (!success) execSql(api, db, "ROLLBACK");
    }

    api.close(db);
    if (success) {
        logLine(ANDROID_LOG_INFO,
                "favorite injection OK name=广州塔 flag=0 position=1; flag=1 current location untouched");
    }
    return success;
}

void injectionWorker() {
    logLine(ANDROID_LOG_INFO, "ZN HYOS Weather worker started pid=%d", getpid());
    for (int attempt = 1; attempt <= 240; ++attempt) {
        if (!gTargetChild.load(std::memory_order_relaxed)) return;

        std::string dbPath = findWeatherDatabase();
        SqliteApi sqlite = loadSqliteApi();
        if (!dbPath.empty() && sqlite.ready()) {
            logLine(ANDROID_LOG_INFO, "Weather native runtime ready attempt=%d db=%s", attempt, dbPath.c_str());
            if (ensureFavorite(sqlite, dbPath)) return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
    logLine(ANDROID_LOG_ERROR, "worker timed out waiting for SQLite/weather.db");
}

void ensureWorkerStarted() {
    if (!gTargetChild.load(std::memory_order_relaxed)) return;
    bool expected = false;
    if (!gWorkerStarted.compare_exchange_strong(expected, true)) return;
    std::thread(injectionWorker).detach();
}

bool isWeatherLibrary(const char *filename) {
    return filename != nullptr && strstr(filename, "libweather_app.so") != nullptr;
}

void *hookDlopen(const char *filename, int flags) {
    void *handle = gOriginalDlopen ? gOriginalDlopen(filename, flags) : nullptr;
    if (handle != nullptr && isWeatherLibrary(filename)) {
        logLine(ANDROID_LOG_INFO, "ZN HYOS observed dlopen: %s", filename);
        ensureWorkerStarted();
    }
    return handle;
}

void *hookAndroidDlopenExt(const char *filename, int flags, const android_dlextinfo *extinfo) {
    void *handle = gOriginalAndroidDlopenExt ? gOriginalAndroidDlopenExt(filename, flags, extinfo) : nullptr;
    if (handle != nullptr && isWeatherLibrary(filename)) {
        logLine(ANDROID_LOG_INFO, "ZN HYOS observed android_dlopen_ext: %s", filename);
        ensureWorkerStarted();
    }
    return handle;
}

void onAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (args == nullptr || args->package_name == nullptr || args->process_name == nullptr) return;
    if (strcmp(args->package_name, kTargetPackage) != 0) return;

    gTargetChild.store(true, std::memory_order_relaxed);
    char line[1024]{};
    snprintf(line, sizeof(line), "ZN HYOS onAppSpecialized package=%s process=%s seinfo=%s pid=%d",
             args->package_name, args->process_name, args->se_info ? args->se_info : "", getpid());
    __android_log_write(ANDROID_LOG_INFO, kTag, line);
    fileLog(line);

    // Do not start threads or perform SQLite work here. The module hooks the
    // spawner's dynamic-loader PLT before specialization; the real worker is
    // launched later, after libweather_app.so has actually been loaded.
}

const ZygiskNextHyosModule kHyosModule = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = onAppSpecialized,
};

void installLoaderHooks(const ZygiskNextAPI *api) {
    if (api->newSymbolResolver == nullptr || api->getBaseAddress == nullptr || api->freeSymbolResolver == nullptr ||
        api->pltHook == nullptr) {
        return;
    }

    ZnSymbolResolver *resolver = api->newSymbolResolver(kSpawnerPath, nullptr);
    if (resolver == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, kTag, "failed to resolve hyos_spawner for loader hooks");
        return;
    }
    void *base = api->getBaseAddress(resolver);
    api->freeSymbolResolver(resolver);
    if (base == nullptr) return;

    int rc1 = api->pltHook(base, "dlopen", reinterpret_cast<void *>(hookDlopen),
                           reinterpret_cast<void **>(&gOriginalDlopen));
    int rc2 = api->pltHook(base, "android_dlopen_ext", reinterpret_cast<void *>(hookAndroidDlopenExt),
                           reinterpret_cast<void **>(&gOriginalAndroidDlopenExt));
    __android_log_print((rc1 == ZN_SUCCESS || rc2 == ZN_SUCCESS) ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, kTag,
                        "loader hooks dlopen=%d android_dlopen_ext=%d", rc1, rc2);
}

void onModuleLoaded(void *, const ZygiskNextAPI *api) {
    if (api == nullptr || api->getRuntime == nullptr) return;
    const ZygiskNextRuntime *runtime = api->getRuntime();
    if (runtime == nullptr || runtime->type != ZN_RUNTIME_HYOS ||
        runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION || runtime->registerModule == nullptr) {
        return;
    }

    installLoaderHooks(api);
    int rc = runtime->registerModule(&kHyosModule);
    __android_log_print(rc == ZN_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "register HYOS module rc=%d api=%d", rc, runtime->api_version);
}

}  // namespace

extern "C" __attribute__((visibility("default"), used)) ZygiskNextModule zn_module = {
        .target_api_version = ZYGISK_NEXT_API_VERSION,
        .onModuleLoaded = onModuleLoaded,
};
