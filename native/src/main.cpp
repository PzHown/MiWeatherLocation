#include "zygisk.hpp"

#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <link.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

constexpr const char *kTag = "MiWeatherLocationNative";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kConfigPath = "/data/adb/miweatherlocation/config.properties";
constexpr uint32_t kMaxConfigBytes = 16 * 1024;

std::mutex gLogMutex;
std::string gLogPath;

void appendFile(const std::string &path, const std::string &text) {
    if (path.empty()) return;
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
    if (fd < 0) return;
    const char *p = text.data();
    size_t left = text.size();
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n <= 0) break;
        p += n;
        left -= static_cast<size_t>(n);
    }
    close(fd);
}

void logLine(int priority, const char *fmt, ...) {
    char buffer[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);

    __android_log_print(priority, kTag, "%s", buffer);

    std::lock_guard<std::mutex> lock(gLogMutex);
    if (!gLogPath.empty()) {
        std::string line(buffer);
        line.push_back('\n');
        appendFile(gLogPath, line);
    }
}

bool writeAll(int fd, const void *data, size_t len) {
    const auto *p = static_cast<const uint8_t *>(data);
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

bool readAll(int fd, void *data, size_t len) {
    auto *p = static_cast<uint8_t *>(data);
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) return false;
        p += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

std::string readFile(const char *path, size_t maxBytes) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string out;
    out.resize(maxBytes);
    ssize_t n = read(fd, out.data(), out.size());
    close(fd);
    if (n <= 0) return {};
    out.resize(static_cast<size_t>(n));
    return out;
}

void companionHandler(int client) {
    std::string data = readFile(kConfigPath, kMaxConfigBytes);
    uint32_t len = static_cast<uint32_t>(std::min<size_t>(data.size(), kMaxConfigBytes));
    if (!writeAll(client, &len, sizeof(len))) return;
    if (len > 0) writeAll(client, data.data(), len);
}

struct Config {
    bool enabled = true;
    std::string posId = "23.106_113.325";
    std::string name = "广州塔";
    std::string streetName = "阅江西路";
    std::string longitude = "113.324521";
    std::string latitude = "23.106428";
    std::string belongings = "广州市, 广东, 中国";
    std::string extra = "weathercn:101280108";
    std::string locale = "zh_cn";
};

std::string trim(std::string s) {
    auto isSpace = [](unsigned char c) { return c == ' ' || c == '\t' || c == '\r' || c == '\n'; };
    while (!s.empty() && isSpace(static_cast<unsigned char>(s.front()))) s.erase(s.begin());
    while (!s.empty() && isSpace(static_cast<unsigned char>(s.back()))) s.pop_back();
    return s;
}

Config parseConfig(const std::string &blob) {
    Config c;
    size_t start = 0;
    while (start < blob.size()) {
        size_t end = blob.find('\n', start);
        if (end == std::string::npos) end = blob.size();
        std::string line = trim(blob.substr(start, end - start));
        start = end + 1;
        if (line.empty() || line[0] == '#') continue;
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;
        std::string key = trim(line.substr(0, eq));
        std::string value = trim(line.substr(eq + 1));
        if (key == "enabled") c.enabled = value != "0" && value != "false";
        else if (key == "pos_id") c.posId = value;
        else if (key == "name") c.name = value;
        else if (key == "street_name") c.streetName = value;
        else if (key == "longitude") c.longitude = value;
        else if (key == "latitude") c.latitude = value;
        else if (key == "belongings") c.belongings = value;
        else if (key == "extra") c.extra = value;
        else if (key == "locale") c.locale = value;
    }
    return c;
}

std::string jstringToString(JNIEnv *env, jstring value) {
    if (env == nullptr || value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

std::string receiveConfig(zygisk::Api *api) {
    if (api == nullptr) return {};
    int fd = api->connectCompanion();
    if (fd < 0) return {};
    uint32_t len = 0;
    if (!readAll(fd, &len, sizeof(len)) || len > kMaxConfigBytes) {
        close(fd);
        return {};
    }
    std::string out;
    if (len > 0) {
        out.resize(len);
        if (!readAll(fd, out.data(), len)) out.clear();
    }
    close(fd);
    return out;
}

std::vector<std::string> databaseCandidates(const std::string &appDataDir) {
    std::vector<std::string> out;
    if (!appDataDir.empty()) {
        out.push_back(appDataDir + "/databases/weather.db");
        const std::string prefix = "/data/user/";
        if (appDataDir.rfind(prefix, 0) == 0) {
            out.push_back("/data/user_de/" + appDataDir.substr(prefix.size()) + "/databases/weather.db");
        }
    }
    out.push_back("/data/user_de/0/com.miui.weather2/databases/weather.db");
    out.push_back("/data/user/0/com.miui.weather2/databases/weather.db");
    out.push_back("/data/data/com.miui.weather2/databases/weather.db");
    return out;
}

std::string chooseLogPath(const std::string &appDataDir) {
    std::vector<std::string> dirs;
    if (!appDataDir.empty()) dirs.push_back(appDataDir + "/files");
    dirs.push_back("/data/user_de/0/com.miui.weather2/files");
    dirs.push_back("/data/user/0/com.miui.weather2/files");
    for (const auto &dir : dirs) {
        if (mkdir(dir.c_str(), 0700) == 0 || errno == EEXIST) {
            if (access(dir.c_str(), W_OK) == 0) return dir + "/miweatherlocation_native.log";
        }
    }
    return {};
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
        return openV2 && close && exec && errMsg && prepareV2 && step && finalize && bindText && columnInt;
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

#define RESOLVE(field, symbol) api.field = reinterpret_cast<SqliteApi::field>(resolveSymbol(api.handle, symbol))
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
#undef RESOLVE
    return api;
}

constexpr int SQLITE_OK = 0;
constexpr int SQLITE_ROW = 100;
constexpr int SQLITE_DONE = 101;
constexpr int SQLITE_OPEN_READWRITE = 0x00000002;
constexpr int SQLITE_OPEN_FULLMUTEX = 0x00010000;

void sqliteTransientDestructor(void *) {}

int execSql(const SqliteApi &api, sqlite3 *db, const char *sql) {
    int rc = api.exec(db, sql, nullptr, nullptr, nullptr);
    if (rc != SQLITE_OK) {
        logLine(ANDROID_LOG_ERROR, "sqlite exec failed rc=%d sql=%s err=%s", rc, sql,
                api.errMsg ? api.errMsg(db) : "unknown");
    }
    return rc;
}

bool bindText(const SqliteApi &api, sqlite3_stmt *stmt, int index, const std::string &value) {
    auto transient = reinterpret_cast<void (*)(void *)>(-1);
    return api.bindText(stmt, index, value.c_str(), -1, transient) == SQLITE_OK;
}

int favoritePosition(const SqliteApi &api, sqlite3 *db, const Config &config) {
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "SELECT position FROM selectedcity WHERE posID=? AND flag=0 LIMIT 1";
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return -1;
    if (!bindText(api, stmt, 1, config.posId)) {
        api.finalize(stmt);
        return -1;
    }
    int rc = api.step(stmt);
    int position = rc == SQLITE_ROW ? api.columnInt(stmt, 0) : -1;
    api.finalize(stmt);
    return position;
}

bool tableExists(const SqliteApi &api, sqlite3 *db) {
    sqlite3_stmt *stmt = nullptr;
    const char *sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='selectedcity' LIMIT 1";
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return false;
    int rc = api.step(stmt);
    api.finalize(stmt);
    return rc == SQLITE_ROW;
}

bool insertFavorite(const SqliteApi &api, sqlite3 *db, const Config &config) {
    const char *sql =
            "INSERT INTO selectedcity "
            "(posID,flag,position,name,street_name,longtitude,latitude,belongings,extra,locale) "
            "VALUES (?,0,1,?,?,?,?,?,?,?)";
    sqlite3_stmt *stmt = nullptr;
    if (api.prepareV2(db, sql, -1, &stmt, nullptr) != SQLITE_OK || stmt == nullptr) return false;
    bool ok = bindText(api, stmt, 1, config.posId)
            && bindText(api, stmt, 2, config.name)
            && bindText(api, stmt, 3, config.streetName)
            && bindText(api, stmt, 4, config.longitude)
            && bindText(api, stmt, 5, config.latitude)
            && bindText(api, stmt, 6, config.belongings)
            && bindText(api, stmt, 7, config.extra)
            && bindText(api, stmt, 8, config.locale);
    int rc = ok ? api.step(stmt) : -1;
    api.finalize(stmt);
    if (rc != SQLITE_DONE) {
        logLine(ANDROID_LOG_ERROR, "favorite insert failed rc=%d err=%s", rc,
                api.errMsg ? api.errMsg(db) : "unknown");
        return false;
    }
    return true;
}

bool ensureFavorite(const SqliteApi &api, const std::string &dbPath, const Config &config) {
    sqlite3 *db = nullptr;
    int rc = api.openV2(dbPath.c_str(), &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr);
    if (rc != SQLITE_OK || db == nullptr) {
        logLine(ANDROID_LOG_WARN, "sqlite open failed rc=%d path=%s", rc, dbPath.c_str());
        if (db != nullptr) api.close(db);
        return false;
    }

    if (api.busyTimeout) api.busyTimeout(db, 3000);
    if (!tableExists(api, db)) {
        logLine(ANDROID_LOG_WARN, "selectedcity table not ready yet");
        api.close(db);
        return false;
    }

    int currentPosition = favoritePosition(api, db, config);
    if (currentPosition == 1) {
        logLine(ANDROID_LOG_INFO, "favorite already present at position=1: %s", config.name.c_str());
        api.close(db);
        return true;
    }

    if (execSql(api, db, "BEGIN IMMEDIATE") != SQLITE_OK) {
        api.close(db);
        return false;
    }

    bool ok = true;
    currentPosition = favoritePosition(api, db, config);
    if (currentPosition == 1) {
        ok = true;
    } else if (currentPosition >= 0) {
        sqlite3_stmt *shift = nullptr;
        const char *shiftSql = "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1 AND posID<>?";
        if (api.prepareV2(db, shiftSql, -1, &shift, nullptr) != SQLITE_OK || shift == nullptr) {
            ok = false;
        } else {
            ok = bindText(api, shift, 1, config.posId) && api.step(shift) == SQLITE_DONE;
            api.finalize(shift);
        }
        if (ok) {
            sqlite3_stmt *move = nullptr;
            const char *moveSql = "UPDATE selectedcity SET position=1 WHERE flag=0 AND posID=?";
            if (api.prepareV2(db, moveSql, -1, &move, nullptr) != SQLITE_OK || move == nullptr) {
                ok = false;
            } else {
                ok = bindText(api, move, 1, config.posId) && api.step(move) == SQLITE_DONE;
                api.finalize(move);
            }
        }
    } else {
        ok = execSql(api, db,
                     "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK;
        if (ok) ok = insertFavorite(api, db, config);
    }

    if (ok) {
        ok = execSql(api, db, "COMMIT") == SQLITE_OK;
    } else {
        execSql(api, db, "ROLLBACK");
    }

    int verifiedPosition = ok ? favoritePosition(api, db, config) : -1;
    api.close(db);

    if (ok && verifiedPosition == 1) {
        logLine(ANDROID_LOG_INFO,
                "favorite injection OK name=%s posID=%s position=1; real location row untouched",
                config.name.c_str(), config.posId.c_str());
        return true;
    }

    logLine(ANDROID_LOG_ERROR, "favorite injection verification failed position=%d", verifiedPosition);
    return false;
}

void worker(std::string appDataDir, Config config, std::string processName) {
    {
        std::lock_guard<std::mutex> lock(gLogMutex);
        gLogPath = chooseLogPath(appDataDir);
    }

    logLine(ANDROID_LOG_INFO,
            "Zygisk entered process=%s appData=%s enabled=%d target=%s",
            processName.c_str(), appDataDir.c_str(), config.enabled ? 1 : 0, config.name.c_str());

    if (!config.enabled) {
        logLine(ANDROID_LOG_INFO, "configuration disabled; no database mutation will be performed");
        return;
    }

    bool weatherLibSeen = false;
    SqliteApi sqlite;
    std::string dbPath;
    const auto candidates = databaseCandidates(appDataDir);

    for (int i = 0; i < 240; ++i) {
        if (!weatherLibSeen) {
            std::string weatherLib = findLoadedLibrary({"libweather_app.so"});
            if (!weatherLib.empty()) {
                weatherLibSeen = true;
                logLine(ANDROID_LOG_INFO, "libweather_app.so loaded: %s", weatherLib.c_str());
            }
        }

        if (!sqlite.ready()) {
            sqlite = loadSqliteApi();
            if (sqlite.ready()) {
                logLine(ANDROID_LOG_INFO, "resolved Xiaomi Weather sqlite3 API");
            }
        }

        if (dbPath.empty()) {
            for (const auto &candidate : candidates) {
                if (access(candidate.c_str(), R_OK | W_OK) == 0) {
                    dbPath = candidate;
                    logLine(ANDROID_LOG_INFO, "weather.db ready: %s", dbPath.c_str());
                    break;
                }
            }
        }

        if (weatherLibSeen && sqlite.ready() && !dbPath.empty()) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }

    if (!weatherLibSeen) {
        logLine(ANDROID_LOG_ERROR, "timeout waiting for libweather_app.so");
        return;
    }
    if (!sqlite.ready()) {
        logLine(ANDROID_LOG_ERROR, "timeout resolving libsqlite3.so symbols");
        return;
    }
    if (dbPath.empty()) {
        logLine(ANDROID_LOG_ERROR, "timeout waiting for writable weather.db");
        return;
    }

    for (int attempt = 1; attempt <= 4; ++attempt) {
        logLine(ANDROID_LOG_INFO, "favorite ensure attempt=%d", attempt);
        if (ensureFavorite(sqlite, dbPath, config)) {
            if (attempt < 4) {
                std::this_thread::sleep_for(std::chrono::seconds(attempt == 1 ? 5 : 10));
                continue;
            }
            return;
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

class MiWeatherLocationModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        processName_ = jstringToString(env_, args->nice_name);
        appDataDir_ = jstringToString(env_, args->app_data_dir);
        target_ = processName_ == kTargetPackage;

        if (!target_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        configBlob_ = receiveConfig(api_);
        flags_ = api_->getFlags();
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!target_) return;
        Config config = parseConfig(configBlob_);
        std::string appDataDir = appDataDir_;
        std::string processName = processName_;
        uint32_t flags = flags_;
        std::thread([appDataDir = std::move(appDataDir), config = std::move(config),
                     processName = std::move(processName), flags]() mutable {
            logLine(ANDROID_LOG_INFO, "postAppSpecialize flags=0x%x", flags);
            worker(std::move(appDataDir), std::move(config), std::move(processName));
        }).detach();
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool target_ = false;
    uint32_t flags_ = 0;
    std::string processName_;
    std::string appDataDir_;
    std::string configBlob_;
};

}  // namespace

REGISTER_ZYGISK_MODULE(MiWeatherLocationModule)
REGISTER_ZYGISK_COMPANION(companionHandler)
