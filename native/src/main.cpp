#include "native_api.h"

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

extern "C" {
struct __attribute__((visibility("default"))) MiWeatherLocationRuntimeState {
    uint32_t magic;
    uint32_t version;
    volatile uint32_t native_init_calls;
    volatile uint32_t target_matches;
    volatile uint32_t library_callbacks;
    volatile uint32_t worker_starts;
    volatile uint32_t weather_runtime_ready;
    volatile uint32_t sqlite_ready;
    volatile uint32_t favorite_present;
    volatile uint32_t favorite_inserted;
    volatile int32_t last_sqlite_rc;
    volatile uint32_t last_attempt;
};

__attribute__((visibility("default"), used))
MiWeatherLocationRuntimeState miweatherlocation_runtime_state = {
        0x4d574c48u, 1u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0u, 0, 0u};
}

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

std::atomic<bool> gWorkerStarted{false};

std::string readSmallFile(const char *path, size_t limit = 512) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string value(limit, '\0');
    ssize_t n;
    do { n = read(fd, value.data(), value.size() - 1); } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return {};
    value.resize(static_cast<size_t>(n));
    size_t zero = value.find('\0');
    if (zero != std::string::npos) value.resize(zero);
    return value;
}

std::string readExecutable() {
    char buffer[256]{};
    ssize_t n = readlink("/proc/self/exe", buffer, sizeof(buffer) - 1);
    if (n <= 0 || static_cast<size_t>(n) >= sizeof(buffer)) return {};
    buffer[n] = '\0';
    return std::string(buffer);
}

std::string readProcessName() { return readSmallFile("/proc/self/cmdline", 256); }

bool isTargetHyosProcess() {
    return readExecutable() == kSpawnerPath && readProcessName() == kTargetPackage;
}

void fileLog(const char *message) {
    static constexpr const char *paths[] = {
            "/data/user_de/0/com.miui.weather2/cache/miweatherlocation_native.log",
            "/data/user/0/com.miui.weather2/cache/miweatherlocation_native.log",
            "/data/data/com.miui.weather2/cache/miweatherlocation_native.log",
    };
    for (const char *path : paths) {
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd < 0) continue;
        size_t len = strnlen(message ? message : "", 2047);
        (void)write(fd, message ? message : "", len);
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
    fileLog(buffer);
}

struct FindLibraryContext { std::vector<std::string> needles; std::string path; };
int findLibraryCallback(dl_phdr_info *info, size_t, void *data) {
    auto *ctx = static_cast<FindLibraryContext *>(data);
    if (!info || !info->dlpi_name || !info->dlpi_name[0]) return 0;
    std::string path(info->dlpi_name);
    for (const auto &needle : ctx->needles) {
        if (path.find(needle) != std::string::npos) { ctx->path = path; return 1; }
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
    void *handle = nullptr; OpenV2 openV2 = nullptr; Close close = nullptr; Exec exec = nullptr;
    ErrMsg errMsg = nullptr; PrepareV2 prepareV2 = nullptr; Step step = nullptr; Finalize finalize = nullptr;
    BindText bindText = nullptr; ColumnInt columnInt = nullptr; BusyTimeout busyTimeout = nullptr;
    bool ready() const { return handle && openV2 && close && exec && errMsg && prepareV2 && step && finalize && bindText && columnInt; }
};

void *resolveSymbol(void *handle, const char *name) {
    void *symbol = dlsym(handle, name);
    if (!symbol && handle != RTLD_DEFAULT) symbol = dlsym(RTLD_DEFAULT, name);
    return symbol;
}
SqliteApi loadSqliteApi() {
    SqliteApi api;
    std::string path = findLoadedLibrary({"libsqlite3.so", "libmisqlite3.so"});
    if (!path.empty()) {
        api.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_NOLOAD);
        if (!api.handle) api.handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    }
    if (!api.handle) api.handle = dlopen("libsqlite3.so", RTLD_NOW | RTLD_NOLOAD);
    if (!api.handle) return api;
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

constexpr int SQLITE_OK = 0, SQLITE_ROW = 100, SQLITE_DONE = 101;
constexpr int SQLITE_OPEN_READWRITE = 0x00000002, SQLITE_OPEN_FULLMUTEX = 0x00010000;
bool bindText(const SqliteApi &api, sqlite3_stmt *stmt, int index, const char *value) {
    return api.bindText(stmt, index, value, -1, reinterpret_cast<void (*)(void *)>(-1)) == SQLITE_OK;
}
int execSql(const SqliteApi &api, sqlite3 *db, const char *sql) {
    int rc = api.exec(db, sql, nullptr, nullptr, nullptr);
    miweatherlocation_runtime_state.last_sqlite_rc = rc;
    if (rc != SQLITE_OK) logLine(ANDROID_LOG_ERROR, "sqlite exec rc=%d sql=%s err=%s", rc, sql, api.errMsg ? api.errMsg(db) : "unknown");
    return rc;
}
bool tableExists(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    if (api.prepareV2(db, "SELECT 1 FROM sqlite_master WHERE type='table' AND name='selectedcity' LIMIT 1", -1, &stmt, nullptr) != SQLITE_OK || !stmt) return false;
    int rc = api.step(stmt); api.finalize(stmt); return rc == SQLITE_ROW;
}
int favoritePosition(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    if (api.prepareV2(db, "SELECT position FROM selectedcity WHERE posID=? AND flag=0 LIMIT 1", -1, &stmt, nullptr) != SQLITE_OK || !stmt) return -1;
    if (!bindText(api, stmt, 1, kPosId)) { api.finalize(stmt); return -1; }
    int rc = api.step(stmt); int position = rc == SQLITE_ROW ? api.columnInt(stmt, 0) : -1; api.finalize(stmt); return position;
}
bool insertFavorite(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "INSERT INTO selectedcity (posID,flag,position,name,street_name,longtitude,latitude,belongings,extra,locale) VALUES (?,0,1,?,?,?,?,?,?,?)";
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || !stmt) return false;
    bool ok = bindText(api, stmt, 1, kPosId) && bindText(api, stmt, 2, kName) && bindText(api, stmt, 3, kStreetName)
            && bindText(api, stmt, 4, kLongitude) && bindText(api, stmt, 5, kLatitude) && bindText(api, stmt, 6, kBelongings)
            && bindText(api, stmt, 7, kExtra) && bindText(api, stmt, 8, kLocale);
    int rc = ok ? api.step(stmt) : -1; api.finalize(stmt); miweatherlocation_runtime_state.last_sqlite_rc = rc;
    if (rc != SQLITE_DONE) { logLine(ANDROID_LOG_ERROR, "favorite insert rc=%d err=%s", rc, api.errMsg ? api.errMsg(db) : "unknown"); return false; }
    return true;
}
std::string findWeatherDatabase() {
    static constexpr const char *candidates[] = {"/data/user_de/0/com.miui.weather2/databases/weather.db", "/data/user/0/com.miui.weather2/databases/weather.db", "/data/data/com.miui.weather2/databases/weather.db"};
    for (const char *path : candidates) if (access(path, R_OK | W_OK) == 0) return path;
    return {};
}
bool ensureFavorite(const SqliteApi &api, const std::string &dbPath) {
    sqlite3 *db = nullptr;
    int rc = api.openV2(dbPath.c_str(), &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr);
    miweatherlocation_runtime_state.last_sqlite_rc = rc;
    if (rc != SQLITE_OK || !db) { if (db) api.close(db); return false; }
    miweatherlocation_runtime_state.sqlite_ready = 1;
    if (api.busyTimeout) api.busyTimeout(db, 3000);
    if (!tableExists(api, db)) { api.close(db); return false; }
    int existing = favoritePosition(api, db);
    if (existing >= 0) {
        miweatherlocation_runtime_state.favorite_present = 1;
        logLine(ANDROID_LOG_INFO, "广州塔 already exists position=%d; real location untouched", existing);
        api.close(db); return true;
    }
    bool success = false;
    if (execSql(api, db, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, db, "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK && insertFavorite(api, db) && execSql(api, db, "COMMIT") == SQLITE_OK) success = favoritePosition(api, db) == 1;
        if (!success) execSql(api, db, "ROLLBACK");
    }
    api.close(db);
    if (success) {
        miweatherlocation_runtime_state.favorite_present = 1;
        miweatherlocation_runtime_state.favorite_inserted = 1;
        logLine(ANDROID_LOG_INFO, "favorite injection OK name=广州塔 flag=0 position=1; flag=1 current location untouched");
    }
    return success;
}

void injectionWorker() {
    miweatherlocation_runtime_state.worker_starts++;
    logLine(ANDROID_LOG_INFO, "HYOS worker started exe=%s process=%s", readExecutable().c_str(), readProcessName().c_str());
    for (int attempt = 1; attempt <= 240; ++attempt) {
        miweatherlocation_runtime_state.last_attempt = static_cast<uint32_t>(attempt);
        if (!isTargetHyosProcess()) return;
        if (findLoadedLibrary({"libweather_app.so"}).empty()) { std::this_thread::sleep_for(std::chrono::milliseconds(250)); continue; }
        miweatherlocation_runtime_state.weather_runtime_ready = 1;
        std::string dbPath = findWeatherDatabase();
        SqliteApi sqlite = loadSqliteApi();
        if (!dbPath.empty() && sqlite.ready()) {
            if (ensureFavorite(sqlite, dbPath)) return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}
void ensureWorkerStarted() {
    if (!isTargetHyosProcess()) return;
    bool expected = false;
    if (!gWorkerStarted.compare_exchange_strong(expected, true)) return;
    std::thread(injectionWorker).detach();
}
void onLibraryLoaded(const char *name, void *) {
    miweatherlocation_runtime_state.library_callbacks++;
    if (!isTargetHyosProcess() || !name) return;
    if (strstr(name, "libweather_app.so") || strstr(name, "libsqlite3.so") || strstr(name, "libmisqlite3.so")) ensureWorkerStarted();
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    miweatherlocation_runtime_state.native_init_calls++;
    if (!entries || !entries->hook_func || !entries->unhook_func || !isTargetHyosProcess()) return nullptr;
    miweatherlocation_runtime_state.target_matches++;
    logLine(ANDROID_LOG_INFO, "native entry initialized in Weather HYOS child apiVersion=%u exe=%s process=%s", entries->version, readExecutable().c_str(), readProcessName().c_str());
    ensureWorkerStarted();
    return onLibraryLoaded;
}
