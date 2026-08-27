#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

extern "C" {
struct MiWeatherLocationRuntimeState {
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

extern MiWeatherLocationRuntimeState miweatherlocation_runtime_state;
int miweatherlocation_toggle_current_favorite();
int miweatherlocation_refresh_current_favorite_state();
}

namespace {

constexpr const char *kTag = "MiWeatherLocationStar";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kSpawnerPath = "/system_ext/bin/hyos_spawner";
constexpr const char *kActionSymbol = "input_MotionEvent_getActionMasked";
constexpr const char *kRawXSymbol = "input_MotionEvent_getRawX";
constexpr const char *kRawYSymbol = "input_MotionEvent_getRawY";
constexpr int kActionDown = 0;
constexpr int kActionUp = 1;
constexpr int kActionCancel = 3;

constexpr int SQLITE_OK = 0;
constexpr int SQLITE_ROW = 100;
constexpr int SQLITE_DONE = 101;
constexpr int SQLITE_OPEN_READWRITE = 0x00000002;
constexpr int SQLITE_OPEN_FULLMUTEX = 0x00010000;

struct sqlite3;
struct sqlite3_stmt;

using SqlOpenV2 = int (*)(const char *, sqlite3 **, int, const char *);
using SqlClose = int (*)(sqlite3 *);
using SqlPrepareV2 = int (*)(sqlite3 *, const char *, int, sqlite3_stmt **, const char **);
using SqlStep = int (*)(sqlite3_stmt *);
using SqlFinalize = int (*)(sqlite3_stmt *);
using SqlColumnInt64 = long long (*)(sqlite3_stmt *, int);
using SqlColumnText = const unsigned char *(*)(sqlite3_stmt *, int);
using SqlBindText = int (*)(sqlite3_stmt *, int, const char *, int, void (*)(void *));
using SqlBindInt64 = int (*)(sqlite3_stmt *, int, long long);
using SqlBusyTimeout = int (*)(sqlite3 *, int);

struct SqliteApi {
    void *handle = nullptr;
    SqlOpenV2 openV2 = nullptr;
    SqlClose close = nullptr;
    SqlPrepareV2 prepareV2 = nullptr;
    SqlStep step = nullptr;
    SqlFinalize finalize = nullptr;
    SqlColumnInt64 columnInt64 = nullptr;
    SqlColumnText columnText = nullptr;
    SqlBindText bindText = nullptr;
    SqlBindInt64 bindInt64 = nullptr;
    SqlBusyTimeout busyTimeout = nullptr;

    bool ready() const {
        return handle && openV2 && close && prepareV2 && step && finalize &&
               columnInt64 && columnText && bindText && bindInt64;
    }
};

using ActionMaskedFn = int (*)(void *event);
using RawCoordinateFn = float (*)(void *event, int pointerIndex);

std::atomic<bool> gThreadStarted{false};
std::atomic<bool> gHookInstalled{false};
std::atomic<bool> gToggleInFlight{false};
std::atomic<uint32_t> gTouchDownCount{0};
std::atomic<uint32_t> gTouchUpCount{0};
std::atomic<uint32_t> gHitCount{0};
ActionMaskedFn gOriginalActionMasked = nullptr;
RawCoordinateFn gRawX = nullptr;
RawCoordinateFn gRawY = nullptr;
std::mutex gDisplayMutex;
std::string gDisplayName;
float gDensity = 3.0f;

struct TouchState {
    bool downInStar = false;
    float downX = 0.0f;
    float downY = 0.0f;
};
thread_local TouchState gTouch;

std::string readSmallFile(const char *path, size_t limit = 256) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    std::string out(limit, '\0');
    ssize_t n;
    do {
        n = read(fd, out.data(), out.size() - 1);
    } while (n < 0 && errno == EINTR);
    close(fd);
    if (n <= 0) return {};
    out.resize(static_cast<size_t>(n));
    size_t zero = out.find('\0');
    if (zero != std::string::npos) out.resize(zero);
    return out;
}

std::string executablePath() {
    char path[256]{};
    ssize_t n = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (n <= 0 || static_cast<size_t>(n) >= sizeof(path)) return {};
    path[n] = '\0';
    return path;
}

bool isTargetProcess() {
    return executablePath() == kSpawnerPath && readSmallFile("/proc/self/cmdline") == kTargetPackage;
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
        size_t length = strnlen(message ? message : "", 1023);
        (void)write(fd, message ? message : "", length);
        (void)write(fd, "\n", 1);
        close(fd);
        return;
    }
}

void logLine(int priority, const char *format, ...) {
    char buffer[1024]{};
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    __android_log_write(priority, kTag, buffer);
    fileLog(buffer);
}

float readDensity() {
    char value[PROP_VALUE_MAX]{};
    if (__system_property_get("ro.sf.lcd_density", value) > 0) {
        int dpi = atoi(value);
        if (dpi >= 160 && dpi <= 800) return static_cast<float>(dpi) / 160.0f;
    }
    return 3.0f;
}

std::string weatherDbPath() {
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

SqliteApi loadSqliteApi() {
    SqliteApi api;
    api.handle = dlopen("libsqlite3.so", RTLD_NOW | RTLD_NOLOAD);
    if (!api.handle) api.handle = dlopen("libsqlite3.so", RTLD_NOW | RTLD_LOCAL);
    if (!api.handle) return api;
    api.openV2 = reinterpret_cast<SqlOpenV2>(dlsym(api.handle, "sqlite3_open_v2"));
    api.close = reinterpret_cast<SqlClose>(dlsym(api.handle, "sqlite3_close"));
    api.prepareV2 = reinterpret_cast<SqlPrepareV2>(dlsym(api.handle, "sqlite3_prepare_v2"));
    api.step = reinterpret_cast<SqlStep>(dlsym(api.handle, "sqlite3_step"));
    api.finalize = reinterpret_cast<SqlFinalize>(dlsym(api.handle, "sqlite3_finalize"));
    api.columnInt64 = reinterpret_cast<SqlColumnInt64>(dlsym(api.handle, "sqlite3_column_int64"));
    api.columnText = reinterpret_cast<SqlColumnText>(dlsym(api.handle, "sqlite3_column_text"));
    api.bindText = reinterpret_cast<SqlBindText>(dlsym(api.handle, "sqlite3_bind_text"));
    api.bindInt64 = reinterpret_cast<SqlBindInt64>(dlsym(api.handle, "sqlite3_bind_int64"));
    api.busyTimeout = reinterpret_cast<SqlBusyTimeout>(dlsym(api.handle, "sqlite3_busy_timeout"));
    return api;
}

std::string columnString(const SqliteApi &api, sqlite3_stmt *statement, int column) {
    const unsigned char *text = api.columnText(statement, column);
    return text ? reinterpret_cast<const char *>(text) : std::string();
}

std::string stripStarSuffix(std::string value) {
    static constexpr const char *suffixes[] = {" ★", " ☆", "★", "☆"};
    for (const char *suffix : suffixes) {
        size_t length = strlen(suffix);
        if (value.size() >= length && value.compare(value.size() - length, length, suffix) == 0) {
            value.erase(value.size() - length);
            while (!value.empty() && value.back() == ' ') value.pop_back();
            break;
        }
    }
    return value;
}

bool bindText(const SqliteApi &api, sqlite3_stmt *statement, int index,
              const std::string &value) {
    return api.bindText(statement, index, value.c_str(), -1,
                        reinterpret_cast<void (*)(void *)>(-1)) == SQLITE_OK;
}

bool syncStarText() {
    if (!isTargetProcess()) return false;
    (void)miweatherlocation_refresh_current_favorite_state();

    SqliteApi api = loadSqliteApi();
    std::string path = weatherDbPath();
    if (!api.ready() || path.empty()) return false;

    sqlite3 *database = nullptr;
    if (api.openV2(path.c_str(), &database,
                   SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX, nullptr) != SQLITE_OK || !database) {
        if (database) api.close(database);
        return false;
    }
    if (api.busyTimeout) api.busyTimeout(database, 1500);

    sqlite3_stmt *query = nullptr;
    const char *querySql =
            "SELECT rowid,name,street_name FROM selectedcity WHERE flag=1 ORDER BY position ASC LIMIT 1";
    if (api.prepareV2(database, querySql, -1, &query, nullptr) != SQLITE_OK || !query) {
        api.close(database);
        return false;
    }
    if (api.step(query) != SQLITE_ROW) {
        api.finalize(query);
        api.close(database);
        return false;
    }

    long long rowId = api.columnInt64(query, 0);
    std::string name = stripStarSuffix(columnString(api, query, 1));
    std::string street = stripStarSuffix(columnString(api, query, 2));
    api.finalize(query);

    bool filled = miweatherlocation_runtime_state.star_filled != 0u;
    std::string display = name;
    if (!street.empty()) {
        if (!display.empty()) display += " ";
        display += street;
    }
    {
        std::lock_guard<std::mutex> lock(gDisplayMutex);
        gDisplayName = display;
    }

    const bool useStreet = !street.empty();
    const std::string base = useStreet ? street : name;
    const std::string desired = base + (filled ? " ★" : " ☆");
    sqlite3_stmt *update = nullptr;
    const char *updateSql = useStreet
            ? "UPDATE selectedcity SET street_name=? WHERE rowid=? AND flag=1"
            : "UPDATE selectedcity SET name=? WHERE rowid=? AND flag=1";
    bool changed = false;
    if (api.prepareV2(database, updateSql, -1, &update, nullptr) == SQLITE_OK && update) {
        if (bindText(api, update, 1, desired) &&
            api.bindInt64(update, 2, rowId) == SQLITE_OK &&
            api.step(update) == SQLITE_DONE) {
            changed = true;
        }
        api.finalize(update);
    }
    api.close(database);
    return changed;
}

float estimateTitleWidthDp(const std::string &text) {
    float width = 0.0f;
    for (size_t i = 0; i < text.size();) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        if (c < 0x80u) {
            width += (c == ' ') ? 4.0f : 8.0f;
            ++i;
        } else {
            size_t step = ((c & 0xE0u) == 0xC0u) ? 2u :
                          ((c & 0xF0u) == 0xE0u) ? 3u : 4u;
            if (i + step > text.size()) step = 1u;
            i += step;
            width += 14.0f;
        }
    }
    return width;
}

bool starHitRegion(float x, float y, float *leftOut = nullptr, float *rightOut = nullptr) {
    std::string display;
    {
        std::lock_guard<std::mutex> lock(gDisplayMutex);
        display = gDisplayName;
    }
    if (display.empty() || gDensity <= 0.0f) return false;

    // The visible star is appended to the current-location title. Different HyperOS
    // font metrics make exact text measurement unavailable from native code, so use
    // a deliberately padded region around the estimated title end. This is still
    // narrow enough to avoid swallowing the entire title row.
    float estimatedEndDp = 24.0f + estimateTitleWidthDp(display);
    float leftDp = std::fmax(92.0f, estimatedEndDp - 34.0f);
    float rightDp = std::fmin(350.0f, estimatedEndDp + 70.0f);
    float topDp = 18.0f;
    float bottomDp = 112.0f;
    if (leftOut) *leftOut = leftDp;
    if (rightOut) *rightOut = rightDp;

    float dpX = x / gDensity;
    float dpY = y / gDensity;
    return dpX >= leftDp && dpX <= rightDp && dpY >= topDp && dpY <= bottomDp;
}

void toggleAsync() {
    bool expected = false;
    if (!gToggleInFlight.compare_exchange_strong(expected, true)) return;
    std::thread([] {
        int result = miweatherlocation_toggle_current_favorite();
        logLine(result > 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                "STAR_TOGGLE result=%d new_state=%s distance=%um",
                result,
                miweatherlocation_runtime_state.star_filled ? "FILLED" : "OUTLINE",
                miweatherlocation_runtime_state.nearest_distance_m);
        std::this_thread::sleep_for(std::chrono::milliseconds(120));
        (void)syncStarText();
        gToggleInFlight.store(false);
    }).detach();
}

int hookedActionMasked(void *event) {
    ActionMaskedFn original = gOriginalActionMasked;
    if (!original) return kActionCancel;
    int action = original(event);
    if (!event || !gRawX || !gRawY) return action;

    if (action == kActionDown) {
        float x = gRawX(event, 0);
        float y = gRawY(event, 0);
        float left = 0.0f;
        float right = 0.0f;
        bool hit = starHitRegion(x, y, &left, &right);
        gTouch.downInStar = hit;
        gTouch.downX = x;
        gTouch.downY = y;
        uint32_t count = gTouchDownCount.fetch_add(1) + 1u;
        if (hit || (y / gDensity) < 130.0f) {
            logLine(ANDROID_LOG_INFO,
                    "TOUCH_DOWN #%u raw=(%.1f,%.1f) dp=(%.1f,%.1f) hit=%d regionX=%.1f..%.1f",
                    count, x, y, x / gDensity, y / gDensity, hit ? 1 : 0, left, right);
        }
    } else if (action == kActionUp) {
        float x = gRawX(event, 0);
        float y = gRawY(event, 0);
        bool upHit = starHitRegion(x, y);
        bool candidate = gTouch.downInStar && upHit;
        float dx = x - gTouch.downX;
        float dy = y - gTouch.downY;
        gTouch.downInStar = false;
        uint32_t count = gTouchUpCount.fetch_add(1) + 1u;
        float maxMove = 22.0f * gDensity;
        bool accepted = candidate && (dx * dx + dy * dy) <= maxMove * maxMove;
        if (candidate || (y / gDensity) < 130.0f) {
            logLine(ANDROID_LOG_INFO,
                    "TOUCH_UP #%u raw=(%.1f,%.1f) candidate=%d accepted=%d move=(%.1f,%.1f)",
                    count, x, y, candidate ? 1 : 0, accepted ? 1 : 0, dx, dy);
        }
        if (accepted) {
            gHitCount.fetch_add(1);
            toggleAsync();
            return kActionCancel;
        }
    } else if (action == kActionCancel) {
        gTouch.downInStar = false;
    }
    return action;
}

uintptr_t relocatedPointer(uintptr_t base, ElfW(Addr) pointer) {
    uintptr_t value = static_cast<uintptr_t>(pointer);
    return value < base ? base + value : value;
}

struct GotPatchContext {
    bool patched = false;
};

int patchGotCallback(dl_phdr_info *info, size_t, void *data) {
    auto *context = static_cast<GotPatchContext *>(data);
    if (!info || !info->dlpi_name || !strstr(info->dlpi_name, "libweather_app.so")) return 0;

    uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    const ElfW(Dyn) *dynamic = nullptr;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dynamic = reinterpret_cast<const ElfW(Dyn) *>(base + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (!dynamic) return 0;

    const ElfW(Sym) *symtab = nullptr;
    const char *strtab = nullptr;
    const ElfW(Rela) *rela = nullptr;
    size_t relaSize = 0;
    for (const ElfW(Dyn) *entry = dynamic; entry->d_tag != DT_NULL; ++entry) {
        switch (entry->d_tag) {
            case DT_SYMTAB:
                symtab = reinterpret_cast<const ElfW(Sym) *>(relocatedPointer(base, entry->d_un.d_ptr));
                break;
            case DT_STRTAB:
                strtab = reinterpret_cast<const char *>(relocatedPointer(base, entry->d_un.d_ptr));
                break;
            case DT_JMPREL:
                rela = reinterpret_cast<const ElfW(Rela) *>(relocatedPointer(base, entry->d_un.d_ptr));
                break;
            case DT_PLTRELSZ:
                relaSize = static_cast<size_t>(entry->d_un.d_val);
                break;
            default:
                break;
        }
    }
    if (!symtab || !strtab || !rela || relaSize == 0) return 0;

    size_t count = relaSize / sizeof(ElfW(Rela));
    long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) pageSize = 4096;
    for (size_t i = 0; i < count; ++i) {
        const ElfW(Rela) &rel = rela[i];
        uint32_t type = static_cast<uint32_t>(ELF64_R_TYPE(rel.r_info));
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
        size_t symbolIndex = static_cast<size_t>(ELF64_R_SYM(rel.r_info));
        const char *name = strtab + symtab[symbolIndex].st_name;
        if (!name || strcmp(name, kActionSymbol) != 0) continue;

        auto **slot = reinterpret_cast<void **>(base + rel.r_offset);
        uintptr_t page = reinterpret_cast<uintptr_t>(slot) & ~static_cast<uintptr_t>(pageSize - 1);
        if (mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize),
                     PROT_READ | PROT_WRITE) != 0) {
            logLine(ANDROID_LOG_ERROR, "HOOK_FAIL mprotect errno=%d", errno);
            return 1;
        }
        gOriginalActionMasked = reinterpret_cast<ActionMaskedFn>(*slot);
        __atomic_store_n(slot, reinterpret_cast<void *>(&hookedActionMasked), __ATOMIC_SEQ_CST);
        (void)mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize), PROT_READ);
        context->patched = true;
        logLine(ANDROID_LOG_INFO, "HOOK_OK symbol=%s original=%p", kActionSymbol,
                reinterpret_cast<void *>(gOriginalActionMasked));
        return 1;
    }
    logLine(ANDROID_LOG_WARN, "HOOK_FAIL symbol_not_found_in_plt");
    return 0;
}

bool installInputHook() {
    if (gHookInstalled.load()) return true;
    gRawX = reinterpret_cast<RawCoordinateFn>(dlsym(RTLD_DEFAULT, kRawXSymbol));
    gRawY = reinterpret_cast<RawCoordinateFn>(dlsym(RTLD_DEFAULT, kRawYSymbol));
    if (!gRawX || !gRawY) {
        logLine(ANDROID_LOG_WARN, "HOOK_WAIT rawX=%p rawY=%p",
                reinterpret_cast<void *>(gRawX), reinterpret_cast<void *>(gRawY));
        return false;
    }
    GotPatchContext context;
    dl_iterate_phdr(patchGotCallback, &context);
    if (context.patched) gHookInstalled.store(true);
    return context.patched;
}

void starWorker() {
    if (!isTargetProcess()) return;
    gDensity = readDensity();
    logLine(ANDROID_LOG_INFO, "STAR_WORKER_START density=%.3f", gDensity);

    for (int i = 0; i < 240 && isTargetProcess(); ++i) {
        (void)syncStarText();
        if (!gHookInstalled.load()) (void)installInputHook();
        if (gHookInstalled.load() && miweatherlocation_runtime_state.current_location_found != 0u) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }

    while (isTargetProcess()) {
        (void)syncStarText();
        if (!gHookInstalled.load()) (void)installInputHook();
        std::this_thread::sleep_for(std::chrono::milliseconds(1200));
    }
}

__attribute__((constructor)) void startStarUiPrototype() {
    if (!isTargetProcess()) return;
    bool expected = false;
    if (!gThreadStarted.compare_exchange_strong(expected, true)) return;
    std::thread(starWorker).detach();
}

}  // namespace
