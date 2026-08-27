#include "native_api.h"

#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <limits>
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
    volatile uint32_t tower_present;
    volatile uint32_t tower_inserted;
    volatile uint32_t current_location_found;
    volatile int32_t current_lat_e6;
    volatile int32_t current_lon_e6;
    volatile uint32_t star_filled;
    volatile uint32_t nearest_distance_m;
    volatile int32_t nearest_favorite_position;
    volatile uint32_t main_page_hidden_count;
    volatile uint32_t toggle_add_count;
    volatile uint32_t toggle_remove_count;
    volatile uint32_t last_action;
    volatile int32_t last_sqlite_rc;
    volatile uint32_t last_attempt;
};

__attribute__((visibility("default"), used))
MiWeatherLocationRuntimeState miweatherlocation_runtime_state = {
        0x4d574c48u, 2u,
        0u, 0u, 0u, 0u, 0u, 0u,
        0u, 0u, 0u,
        0, 0,
        0u, 0u, -1,
        0u, 0u, 0u, 0u,
        0, 0u};
}

namespace {

constexpr const char *kTag = "MiWeatherLocationNative";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kSpawnerPath = "/system_ext/bin/hyos_spawner";
constexpr double kFavoriteRadiusMeters = 2000.0;
constexpr double kEarthRadiusMeters = 6371000.0;

constexpr const char *kTowerPosId = "23.106_113.325";
constexpr const char *kTowerName = "广州塔";
constexpr const char *kTowerStreet = "阅江西路";
constexpr const char *kTowerLongitude = "113.324521";
constexpr const char *kTowerLatitude = "23.106428";
constexpr const char *kTowerBelongings = "广州市, 广东, 中国";
constexpr const char *kTowerExtra = "weathercn:101280108";
constexpr const char *kDefaultLocale = "zh_cn";

constexpr int SQLITE_OK = 0;
constexpr int SQLITE_ROW = 100;
constexpr int SQLITE_DONE = 101;
constexpr int SQLITE_OPEN_READWRITE = 0x00000002;
constexpr int SQLITE_OPEN_FULLMUTEX = 0x00010000;

std::atomic<bool> gWorkerStarted{false};

void incrementCounter(volatile uint32_t &value) {
    value = value + 1u;
}

std::string readSmallFile(const char *path, size_t limit = 512) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string value(limit, '\0');
    ssize_t n;
    do {
        n = read(fd, value.data(), value.size() - 1);
    } while (n < 0 && errno == EINTR);
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
    return buffer;
}

std::string readProcessName() {
    return readSmallFile("/proc/self/cmdline", 256);
}

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
        size_t length = strnlen(message ? message : "", 2047);
        (void)write(fd, message ? message : "", length);
        (void)write(fd, "\n", 1);
        close(fd);
        return;
    }
}

void logLine(int priority, const char *format, ...) {
    char buffer[2048]{};
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    __android_log_write(priority, kTag, buffer);
    fileLog(buffer);
}

struct FindLibraryContext {
    std::vector<std::string> needles;
    std::string path;
};

int findLibraryCallback(dl_phdr_info *info, size_t, void *data) {
    auto *context = static_cast<FindLibraryContext *>(data);
    if (!info || !info->dlpi_name || !info->dlpi_name[0]) return 0;
    std::string path(info->dlpi_name);
    for (const auto &needle : context->needles) {
        if (path.find(needle) != std::string::npos) {
            context->path = path;
            return 1;
        }
    }
    return 0;
}

std::string findLoadedLibrary(std::initializer_list<const char *> names) {
    FindLibraryContext context;
    for (const char *name : names) context.needles.emplace_back(name);
    dl_iterate_phdr(findLibraryCallback, &context);
    return context.path;
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
    using ColumnInt64 = long long (*)(sqlite3_stmt *, int);
    using ColumnText = const unsigned char *(*)(sqlite3_stmt *, int);
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
    ColumnInt64 columnInt64 = nullptr;
    ColumnText columnText = nullptr;
    BusyTimeout busyTimeout = nullptr;

    bool ready() const {
        return handle && openV2 && close && exec && errMsg && prepareV2 && step && finalize &&
               bindText && columnInt && columnInt64 && columnText;
    }
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
    api.columnInt64 = reinterpret_cast<SqliteApi::ColumnInt64>(resolveSymbol(api.handle, "sqlite3_column_int64"));
    api.columnText = reinterpret_cast<SqliteApi::ColumnText>(resolveSymbol(api.handle, "sqlite3_column_text"));
    api.busyTimeout = reinterpret_cast<SqliteApi::BusyTimeout>(resolveSymbol(api.handle, "sqlite3_busy_timeout"));
    return api;
}

bool sqlBindText(const SqliteApi &api, sqlite3_stmt *statement, int index, const std::string &value) {
    return api.bindText(statement, index, value.c_str(), -1,
                        reinterpret_cast<void (*)(void *)>(-1)) == SQLITE_OK;
}

std::string columnString(const SqliteApi &api, sqlite3_stmt *statement, int index) {
    const unsigned char *text = api.columnText(statement, index);
    return text ? reinterpret_cast<const char *>(text) : std::string();
}

int execSql(const SqliteApi &api, sqlite3 *database, const char *sql) {
    int rc = api.exec(database, sql, nullptr, nullptr, nullptr);
    miweatherlocation_runtime_state.last_sqlite_rc = rc;
    if (rc != SQLITE_OK) {
        logLine(ANDROID_LOG_ERROR, "sqlite rc=%d sql=%s err=%s", rc, sql,
                api.errMsg ? api.errMsg(database) : "unknown");
    }
    return rc;
}

bool selectedCityTableExists(const SqliteApi &api, sqlite3 *database) {
    sqlite3_stmt *statement = nullptr;
    const char *sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='selectedcity' LIMIT 1";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return false;
    int rc = api.step(statement);
    api.finalize(statement);
    return rc == SQLITE_ROW;
}

bool parseCoordinate(const std::string &text, double *out) {
    if (!out || text.empty()) return false;
    char *end = nullptr;
    errno = 0;
    double value = strtod(text.c_str(), &end);
    if (errno != 0 || end == text.c_str() || !std::isfinite(value)) return false;
    while (*end == ' ' || *end == '\t' || *end == '\r' || *end == '\n') ++end;
    if (*end != '\0') return false;
    *out = value;
    return true;
}

double degreesToRadians(double degrees) {
    return degrees * 3.14159265358979323846 / 180.0;
}

double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double dLat = degreesToRadians(lat2 - lat1);
    double dLon = degreesToRadians(lon2 - lon1);
    double sinLat = std::sin(dLat / 2.0);
    double sinLon = std::sin(dLon / 2.0);
    double a = sinLat * sinLat + std::cos(degreesToRadians(lat1)) *
               std::cos(degreesToRadians(lat2)) * sinLon * sinLon;
    if (a < 0.0) a = 0.0;
    if (a > 1.0) a = 1.0;
    return kEarthRadiusMeters * 2.0 * std::atan2(std::sqrt(a), std::sqrt(1.0 - a));
}

struct CityRecord {
    long long rowId = -1;
    int position = -1;
    std::string posId;
    std::string name;
    std::string street;
    std::string longitudeText;
    std::string latitudeText;
    std::string belongings;
    std::string extra;
    std::string locale;
    double longitude = 0.0;
    double latitude = 0.0;
    bool coordinatesValid = false;
};

CityRecord readCityRecord(const SqliteApi &api, sqlite3_stmt *statement) {
    CityRecord city;
    city.rowId = api.columnInt64(statement, 0);
    city.position = api.columnInt(statement, 1);
    city.posId = columnString(api, statement, 2);
    city.name = columnString(api, statement, 3);
    city.street = columnString(api, statement, 4);
    city.longitudeText = columnString(api, statement, 5);
    city.latitudeText = columnString(api, statement, 6);
    city.belongings = columnString(api, statement, 7);
    city.extra = columnString(api, statement, 8);
    city.locale = columnString(api, statement, 9);
    city.coordinatesValid = parseCoordinate(city.longitudeText, &city.longitude) &&
                            parseCoordinate(city.latitudeText, &city.latitude);
    return city;
}

bool readCurrentLocation(const SqliteApi &api, sqlite3 *database, CityRecord *out) {
    if (!out) return false;
    sqlite3_stmt *statement = nullptr;
    const char *sql =
            "SELECT rowid,position,posID,name,street_name,longtitude,latitude,belongings,extra,locale "
            "FROM selectedcity WHERE flag=1 ORDER BY position ASC LIMIT 1";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return false;
    if (api.step(statement) != SQLITE_ROW) {
        api.finalize(statement);
        return false;
    }
    *out = readCityRecord(api, statement);
    api.finalize(statement);
    return true;
}

struct FavoriteMatch {
    bool found = false;
    CityRecord city;
    double meters = std::numeric_limits<double>::infinity();
    uint32_t withinRadiusCount = 0u;
};

FavoriteMatch findNearestFavorite(const SqliteApi &api, sqlite3 *database,
                                  const CityRecord &current) {
    FavoriteMatch match;
    if (!current.coordinatesValid) return match;

    sqlite3_stmt *statement = nullptr;
    const char *sql =
            "SELECT rowid,position,posID,name,street_name,longtitude,latitude,belongings,extra,locale "
            "FROM selectedcity WHERE flag=0 ORDER BY position ASC";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return match;

    while (api.step(statement) == SQLITE_ROW) {
        CityRecord candidate = readCityRecord(api, statement);
        if (!candidate.coordinatesValid) continue;
        double meters = distanceMeters(current.latitude, current.longitude,
                                       candidate.latitude, candidate.longitude);
        if (meters <= kFavoriteRadiusMeters) ++match.withinRadiusCount;
        if (!match.found || meters < match.meters) {
            match.found = true;
            match.city = candidate;
            match.meters = meters;
        }
    }
    api.finalize(statement);
    return match;
}

void updateRuntimeFavoriteState(const CityRecord &current, const FavoriteMatch &match) {
    bool filled = match.found && match.meters <= kFavoriteRadiusMeters;
    miweatherlocation_runtime_state.current_location_found = current.coordinatesValid ? 1u : 0u;
    miweatherlocation_runtime_state.current_lat_e6 = current.coordinatesValid
            ? static_cast<int32_t>(std::llround(current.latitude * 1000000.0)) : 0;
    miweatherlocation_runtime_state.current_lon_e6 = current.coordinatesValid
            ? static_cast<int32_t>(std::llround(current.longitude * 1000000.0)) : 0;
    miweatherlocation_runtime_state.star_filled = filled ? 1u : 0u;
    miweatherlocation_runtime_state.nearest_distance_m = match.found
            ? static_cast<uint32_t>(std::llround(match.meters)) : 0u;
    miweatherlocation_runtime_state.nearest_favorite_position = match.found
            ? match.city.position : -1;
    miweatherlocation_runtime_state.main_page_hidden_count = match.withinRadiusCount;

    logLine(ANDROID_LOG_INFO,
            "2km state current=%s %s star=%s nearest=%s %s distance=%.0fm hidden=%u",
            current.name.c_str(), current.street.c_str(), filled ? "FILLED" : "OUTLINE",
            match.found ? match.city.name.c_str() : "none",
            match.found ? match.city.street.c_str() : "",
            match.found ? match.meters : -1.0,
            match.withinRadiusCount);
}

bool refreshFavoriteState(const SqliteApi &api, sqlite3 *database) {
    CityRecord current;
    if (!readCurrentLocation(api, database, &current) || !current.coordinatesValid) {
        miweatherlocation_runtime_state.current_location_found = 0u;
        miweatherlocation_runtime_state.star_filled = 0u;
        miweatherlocation_runtime_state.main_page_hidden_count = 0u;
        return false;
    }
    updateRuntimeFavoriteState(current, findNearestFavorite(api, database, current));
    return true;
}

int towerPosition(const SqliteApi &api, sqlite3 *database) {
    sqlite3_stmt *statement = nullptr;
    const char *sql = "SELECT position FROM selectedcity WHERE posID=? AND flag=0 LIMIT 1";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return -1;
    if (!sqlBindText(api, statement, 1, kTowerPosId)) {
        api.finalize(statement);
        return -1;
    }
    int rc = api.step(statement);
    int position = rc == SQLITE_ROW ? api.columnInt(statement, 0) : -1;
    api.finalize(statement);
    return position;
}

bool insertTower(const SqliteApi &api, sqlite3 *database) {
    sqlite3_stmt *statement = nullptr;
    const char *sql =
            "INSERT INTO selectedcity "
            "(posID,flag,position,name,street_name,longtitude,latitude,belongings,extra,locale) "
            "VALUES (?,0,1,?,?,?,?,?,?,?)";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return false;

    bool ok = sqlBindText(api, statement, 1, kTowerPosId) &&
              sqlBindText(api, statement, 2, kTowerName) &&
              sqlBindText(api, statement, 3, kTowerStreet) &&
              sqlBindText(api, statement, 4, kTowerLongitude) &&
              sqlBindText(api, statement, 5, kTowerLatitude) &&
              sqlBindText(api, statement, 6, kTowerBelongings) &&
              sqlBindText(api, statement, 7, kTowerExtra) &&
              sqlBindText(api, statement, 8, kDefaultLocale);
    int rc = ok ? api.step(statement) : -1;
    api.finalize(statement);
    return rc == SQLITE_DONE;
}

bool ensureTower(const SqliteApi &api, sqlite3 *database) {
    if (towerPosition(api, database) >= 0) {
        miweatherlocation_runtime_state.tower_present = 1u;
        return true;
    }

    bool success = false;
    if (execSql(api, database, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, database,
                    "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK &&
            insertTower(api, database) &&
            execSql(api, database, "COMMIT") == SQLITE_OK) {
            success = true;
        }
        if (!success) execSql(api, database, "ROLLBACK");
    }
    if (success) {
        miweatherlocation_runtime_state.tower_present = 1u;
        miweatherlocation_runtime_state.tower_inserted = 1u;
        logLine(ANDROID_LOG_INFO, "favorite injection OK name=广州塔 flag=0 position=1");
    }
    return success;
}

std::string makeCurrentPosId(const CityRecord &current) {
    char buffer[96]{};
    snprintf(buffer, sizeof(buffer), "%.3f_%.3f", current.latitude, current.longitude);
    return buffer;
}

bool insertCurrentFavorite(const SqliteApi &api, sqlite3 *database, const CityRecord &current) {
    sqlite3_stmt *statement = nullptr;
    const char *sql =
            "INSERT INTO selectedcity "
            "(posID,flag,position,name,street_name,longtitude,latitude,belongings,extra,locale) "
            "VALUES (?,0,1,?,?,?,?,?,?,?)";
    if (api.prepareV2(database, sql, -1, &statement, nullptr) != SQLITE_OK || !statement) return false;

    std::string posId = makeCurrentPosId(current);
    std::string locale = current.locale.empty() ? kDefaultLocale : current.locale;
    bool ok = sqlBindText(api, statement, 1, posId) &&
              sqlBindText(api, statement, 2, current.name) &&
              sqlBindText(api, statement, 3, current.street) &&
              sqlBindText(api, statement, 4, current.longitudeText) &&
              sqlBindText(api, statement, 5, current.latitudeText) &&
              sqlBindText(api, statement, 6, current.belongings) &&
              sqlBindText(api, statement, 7, current.extra) &&
              sqlBindText(api, statement, 8, locale);
    int rc = ok ? api.step(statement) : -1;
    api.finalize(statement);
    miweatherlocation_runtime_state.last_sqlite_rc = rc;
    return rc == SQLITE_DONE;
}

bool addCurrentFavorite(const SqliteApi &api, sqlite3 *database, const CityRecord &current) {
    bool success = false;
    if (execSql(api, database, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, database,
                    "UPDATE selectedcity SET position=position+1 WHERE flag=0 AND position>=1") == SQLITE_OK &&
            insertCurrentFavorite(api, database, current) &&
            execSql(api, database, "COMMIT") == SQLITE_OK) {
            success = true;
        }
        if (!success) execSql(api, database, "ROLLBACK");
    }
    if (success) {
        incrementCounter(miweatherlocation_runtime_state.toggle_add_count);
        miweatherlocation_runtime_state.last_action = 1u;
        logLine(ANDROID_LOG_INFO, "2km favorite ADD %s %s",
                current.name.c_str(), current.street.c_str());
    }
    return success;
}

bool removeNearestFavorite(const SqliteApi &api, sqlite3 *database, const FavoriteMatch &match) {
    if (!match.found || match.meters > kFavoriteRadiusMeters || match.city.rowId < 0) return false;

    char deleteSql[192]{};
    char reorderSql[192]{};
    snprintf(deleteSql, sizeof(deleteSql),
             "DELETE FROM selectedcity WHERE rowid=%lld AND flag=0", match.city.rowId);
    snprintf(reorderSql, sizeof(reorderSql),
             "UPDATE selectedcity SET position=position-1 WHERE flag=0 AND position>%d",
             match.city.position);

    bool success = false;
    if (execSql(api, database, "BEGIN IMMEDIATE") == SQLITE_OK) {
        if (execSql(api, database, deleteSql) == SQLITE_OK &&
            execSql(api, database, reorderSql) == SQLITE_OK &&
            execSql(api, database, "COMMIT") == SQLITE_OK) {
            success = true;
        }
        if (!success) execSql(api, database, "ROLLBACK");
    }
    if (success) {
        incrementCounter(miweatherlocation_runtime_state.toggle_remove_count);
        miweatherlocation_runtime_state.last_action = 2u;
        logLine(ANDROID_LOG_INFO, "2km favorite REMOVE %s %s distance=%.0fm",
                match.city.name.c_str(), match.city.street.c_str(), match.meters);
    }
    return success;
}

int toggleFavorite(const SqliteApi &api, sqlite3 *database) {
    CityRecord current;
    if (!readCurrentLocation(api, database, &current) || !current.coordinatesValid) return -2;

    FavoriteMatch before = findNearestFavorite(api, database, current);
    bool wasFilled = before.found && before.meters <= kFavoriteRadiusMeters;
    bool success = wasFilled
            ? removeNearestFavorite(api, database, before)
            : addCurrentFavorite(api, database, current);

    FavoriteMatch after = findNearestFavorite(api, database, current);
    updateRuntimeFavoriteState(current, after);
    if (!success) return -1;
    return wasFilled ? 2 : 1;
}

std::string findWeatherDatabase() {
    static constexpr const char *paths[] = {
            "/data/user_de/0/com.miui.weather2/databases/weather.db",
            "/data/user/0/com.miui.weather2/databases/weather.db",
            "/data/data/com.miui.weather2/databases/weather.db",
    };
    for (const char *path : paths) {
        if (access(path, R_OK | W_OK) == 0) return path;
    }
    return {};
}

bool withWeatherDatabase(const SqliteApi &api, sqlite3 **database, std::string *pathOut = nullptr) {
    if (!database) return false;
    std::string path = findWeatherDatabase();
    if (path.empty()) return false;
    sqlite3 *db = nullptr;
    int rc = api.openV2(path.c_str(), &db,
                        SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr);
    miweatherlocation_runtime_state.last_sqlite_rc = rc;
    if (rc != SQLITE_OK || !db) {
        if (db) api.close(db);
        return false;
    }
    if (api.busyTimeout) api.busyTimeout(db, 3000);
    *database = db;
    if (pathOut) *pathOut = path;
    return true;
}

bool initializeDatabaseFeatures(const SqliteApi &api) {
    sqlite3 *database = nullptr;
    std::string path;
    if (!withWeatherDatabase(api, &database, &path)) return false;
    miweatherlocation_runtime_state.sqlite_ready = 1u;

    if (!selectedCityTableExists(api, database)) {
        api.close(database);
        return false;
    }

    logLine(ANDROID_LOG_INFO, "Weather DB ready path=%s radius=2000m", path.c_str());
    bool towerOk = ensureTower(api, database);
    (void)refreshFavoriteState(api, database);
    api.close(database);
    return towerOk;
}

void injectionWorker() {
    incrementCounter(miweatherlocation_runtime_state.worker_starts);
    for (int attempt = 1; attempt <= 240; ++attempt) {
        miweatherlocation_runtime_state.last_attempt = static_cast<uint32_t>(attempt);
        if (!isTargetHyosProcess()) return;
        if (findLoadedLibrary({"libweather_app.so"}).empty()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            continue;
        }
        miweatherlocation_runtime_state.weather_runtime_ready = 1u;
        SqliteApi api = loadSqliteApi();
        if (api.ready() && initializeDatabaseFeatures(api)) return;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
    logLine(ANDROID_LOG_ERROR, "worker timed out waiting for Weather DB/runtime");
}

void ensureWorkerStarted() {
    if (!isTargetHyosProcess()) return;
    bool expected = false;
    if (gWorkerStarted.compare_exchange_strong(expected, true)) {
        std::thread(injectionWorker).detach();
    }
}

void onLibraryLoaded(const char *name, void *) {
    incrementCounter(miweatherlocation_runtime_state.library_callbacks);
    if (!isTargetHyosProcess() || !name) return;
    if (strstr(name, "libweather_app.so") ||
        strstr(name, "libsqlite3.so") ||
        strstr(name, "libmisqlite3.so")) {
        ensureWorkerStarted();
    }
}

}  // namespace

extern "C" __attribute__((visibility("default"), used))
int miweatherlocation_toggle_current_favorite() {
    if (!isTargetHyosProcess()) return -3;
    SqliteApi api = loadSqliteApi();
    if (!api.ready()) return -4;
    sqlite3 *database = nullptr;
    if (!withWeatherDatabase(api, &database)) return -5;
    int result = selectedCityTableExists(api, database)
            ? toggleFavorite(api, database)
            : -6;
    api.close(database);
    return result;
}

extern "C" __attribute__((visibility("default"), used))
int miweatherlocation_refresh_current_favorite_state() {
    if (!isTargetHyosProcess()) return -3;
    SqliteApi api = loadSqliteApi();
    if (!api.ready()) return -4;
    sqlite3 *database = nullptr;
    if (!withWeatherDatabase(api, &database)) return -5;
    bool ok = selectedCityTableExists(api, database) && refreshFavoriteState(api, database);
    api.close(database);
    return ok ? 0 : -1;
}

extern "C" __attribute__((visibility("default"), used))
NativeOnModuleLoaded native_init(const NativeAPIEntries *entries) {
    incrementCounter(miweatherlocation_runtime_state.native_init_calls);
    if (!entries || !entries->hook_func || !entries->unhook_func || !isTargetHyosProcess()) {
        return nullptr;
    }
    incrementCounter(miweatherlocation_runtime_state.target_matches);
    logLine(ANDROID_LOG_INFO,
            "native entry initialized in Weather HYOS child apiVersion=%u exe=%s process=%s",
            entries->version, readExecutable().c_str(), readProcessName().c_str());
    ensureWorkerStarted();
    return onLibraryLoaded;
}
