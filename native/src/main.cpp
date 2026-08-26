#include "native_api.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <link.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char *kTag = "MiWeatherLocationNative";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kPosId = "23.106_113.325";
constexpr const char *kName = "广州塔";
constexpr const char *kStreetName = "阅江西路";
constexpr const char *kLongitude = "113.324521";
constexpr const char *kLatitude = "23.106428";
constexpr const char *kBelongings = "广州市, 广东, 中国";
constexpr const char *kExtra = "weathercn:101280108";
constexpr const char *kLocale = "zh_cn";

std::atomic<bool> gWorkerStarted{false};

void logLine(int priority, const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    __android_log_print(priority, kTag, "%s", buffer);
}

std::string readProcessName() {
    FILE *fp = fopen("/proc/self/cmdline", "rb");
    if (fp == nullptr) return {};
    char buffer[256]{};
    size_t n = fread(buffer, 1, sizeof(buffer) - 1, fp);
    fclose(fp);
    if (n == 0) return {};
    buffer[sizeof(buffer) - 1] = '\0';
    return std::string(buffer);
}

bool isTargetProcess() {
    const std::string process = readProcessName();
    if (process == kTargetPackage) return true;
    const std::string prefix = std::string(kTargetPackage) + ":";
    return process.rfind(prefix, 0) == 0;
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
        return handle != nullptr && openV2 && close && exec && errMsg && prepareV2
                && step && finalize && bindText && columnInt;
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
    if (api.handle == nullptr) {
        api.handle = dlopen("libsqlite3.so", RTLD_NOW | RTLD_NOLOAD);
    }
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

    bool ok = bindText(api, stmt, 1, kPosId)
            && bindText(api, stmt, 2, kName)
            && bindText(api, stmt, 3, kStreetName)
            && bindText(api, stmt, 4, kLongitude)
            && bindText(api, stmt, 5, kLatitude)
            && bindText(api, stmt, 6, kBelongings)
            && bindText(api, stmt, 7, kExtra)
            && bindText(api, stmt, 8, kLocale);

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
    static const char *candidates[] = {
            "/data/user_de/0/com.miui.weather2/databases/weather.db",
            "/data/user/0/com.miui.weather2/databases/weather.db",
            "/data/data/com.miui.weather2/databases/weather.db"
    };
    for (const char *path : candidates) {
        if (access(path, R_OK | W_OK) == 0) return path;
    }
    return {};
}

bool ensureFavorite(const SqliteApi &api, const std::string &dbPath) {
    sqlite3 *db = nullptr;
    int rc = api.openV2(dbPath.c_str(), &db,
                        SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr);
    if (rc != SQLITE_OK || db == nullptr) {
        logLine(ANDROID_LOG_WARN, "open weather.db failed rc=%d path=%s", rc, dbPath.c_str());
        if (db != nullptr) api.close(db);
        return false;
    }

    if (api.busyTimeout) api.busyTimeout(db, 3000);
    bool success = false;

    if (!tableExists(api, db)) {
        logLine(ANDROID_LOG_WARN, "selectedcity not ready yet");
        api.close(db);
        return false;
    }

    int existing = favoritePosition(api, db);
    if (existing >= 0) {
        logLine(ANDROID_LOG_INFO, "广州塔 already exists position=%d; real location untouched", existing);
        api.close(db);
        return true;
    }

    if (execSql(api, db, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, db,
                    "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK
                && insertFavorite(api, db)) {
            if (execSql(api, db, "COMMIT") == SQLITE_OK) {
                success = favoritePosition(api, db) == 1;
            }
        }
        if (!success) execSql(api, db, "ROLLBACK");
    }

    api.close(db);
    if (success) {
        logLine(ANDROID_LOG_INFO,
                "favorite injection OK: 广州塔 flag=0 position=1; flag=1 current location was never modified");
    }
    return success;
}

void injectionWorker() {
    logLine(ANDROID_LOG_INFO, "LSPosed native worker entered process=%s", readProcessName().c_str());

    for (int attempt = 1; attempt <= 160; ++attempt) {
        if (findLoadedLibrary({"libweather_app.so"}).empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            continue;
        }

        std::string dbPath = findWeatherDatabase();
        SqliteApi sqlite = loadSqliteApi();
        if (!dbPath.empty() && sqlite.ready()) {
            logLine(ANDROID_LOG_INFO, "Weather native runtime ready attempt=%d db=%s", attempt, dbPath.c_str());
            if (ensureFavorite(sqlite, dbPath)) return;
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }

    logLine(ANDROID_LOG_ERROR,
            "native worker timed out waiting for libweather_app.so/sqlite/weather.db");
}

void ensureWorkerStarted() {
    if (!isTargetProcess()) return;
    bool expected = false;
    if (!gWorkerStarted.compare_exchange_strong(expected, true)) return;
    std::thread(injectionWorker).detach();
}

void onLibraryLoaded(const char *name, void *) {
    if (!isTargetProcess() || name == nullptr) return;
    if (strstr(name, "libweather_app.so") != nullptr
            || strstr(name, "libsqlite3.so") != nullptr
            || strstr(name, "libmisqlite3.so") != nullptr) {
        logLine(ANDROID_LOG_INFO, "LSPosed observed library load: %s", name);
        ensureWorkerStarted();
    }
}

}  // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    if (isTargetProcess()) {
        logLine(ANDROID_LOG_INFO, "native_init apiVersion=%u process=%s",
                entries ? entries->version : 0, readProcessName().c_str());
        ensureWorkerStarted();
    }
    return onLibraryLoaded;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *, void *) {
    if (isTargetProcess()) {
        logLine(ANDROID_LOG_INFO, "JNI_OnLoad from APK-embedded LSPosed native payload");
        ensureWorkerStarted();
    }
    return JNI_VERSION_1_6;
}
