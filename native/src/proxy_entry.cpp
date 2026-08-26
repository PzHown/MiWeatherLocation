#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace {

constexpr const char* kTag = "MiWeatherLocationProxy";
constexpr const char* kOriginalEnv = "MIWEATHERLOCATION_ORIGINAL_BINARY";

std::mutex gLoadMutex;
void* gOriginalHandle = nullptr;
std::atomic<bool> gWorkerTriggered{false};

void logLine(int priority, const char* text) {
    __android_log_print(priority, kTag, "%s", text == nullptr ? "" : text);
}

void* loadOriginalWeatherBinary() {
    std::lock_guard<std::mutex> lock(gLoadMutex);
    if (gOriginalHandle != nullptr) return gOriginalHandle;

    const char* path = std::getenv(kOriginalEnv);
    if (path == nullptr || path[0] == '\0') {
        logLine(ANDROID_LOG_ERROR, "proxy entered but original binary env is missing");
        return nullptr;
    }

    std::string message = std::string("loading original Weather binary: ") + path;
    logLine(ANDROID_LOG_INFO, message.c_str());
    dlerror();
    gOriginalHandle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (gOriginalHandle == nullptr) {
        const char* error = dlerror();
        std::string failure = std::string("dlopen original Weather binary failed: ")
                + (error == nullptr ? "unknown" : error);
        logLine(ANDROID_LOG_ERROR, failure.c_str());
        return nullptr;
    }

    logLine(ANDROID_LOG_INFO, "original Weather binary loaded");
    return gOriginalHandle;
}

extern "C" jint JNI_OnLoad(JavaVM*, void*);

void triggerDatabaseWorker() {
    bool expected = false;
    if (!gWorkerTriggered.compare_exchange_strong(expected, true)) return;
    // The existing JNI_OnLoad implementation does not use JavaVM and starts
    // the Weather-native DB worker. hyos_spawner loads this library as a Rust
    // binary rather than a JNI library, so invoke it explicitly once.
    JNI_OnLoad(nullptr, nullptr);
}

uintptr_t resolveOriginalEntry(const char* preferred) {
    void* handle = loadOriginalWeatherBinary();
    if (handle == nullptr) return 0;

    dlerror();
    void* entry = dlsym(handle, preferred);
    const char* error = dlerror();
    if (entry == nullptr) {
        const char* fallback = std::strcmp(preferred, "app_entry_point") == 0
                ? "hy_app_init" : "app_entry_point";
        dlerror();
        entry = dlsym(handle, fallback);
        error = dlerror();
    }
    if (entry == nullptr) {
        std::string failure = std::string("original Weather entry unresolved preferred=")
                + preferred + " error=" + (error == nullptr ? "unknown" : error);
        logLine(ANDROID_LOG_ERROR, failure.c_str());
        return 0;
    }

    triggerDatabaseWorker();
    std::string ready = std::string("proxy forwarding to original entry preferred=") + preferred;
    logLine(ANDROID_LOG_INFO, ready.c_str());
    return reinterpret_cast<uintptr_t>(entry);
}

}  // namespace

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
uintptr_t miweather_resolve_app_entry_point() {
    return resolveOriginalEntry("app_entry_point");
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
uintptr_t miweather_resolve_hy_app_init() {
    return resolveOriginalEntry("hy_app_init");
}

#if defined(__aarch64__)
#define MIWEATHER_PROXY_TAIL_ENTRY(symbol_name, resolver_name) \
extern "C" [[gnu::visibility("default")]] [[gnu::used]] [[gnu::naked]] \
void symbol_name() { \
    __asm__ volatile( \
            "sub sp, sp, #96\n" \
            "stp x0, x1, [sp, #0]\n" \
            "stp x2, x3, [sp, #16]\n" \
            "stp x4, x5, [sp, #32]\n" \
            "stp x6, x7, [sp, #48]\n" \
            "str x8, [sp, #64]\n" \
            "str x30, [sp, #72]\n" \
            "bl " #resolver_name "\n" \
            "mov x9, x0\n" \
            "ldp x0, x1, [sp, #0]\n" \
            "ldp x2, x3, [sp, #16]\n" \
            "ldp x4, x5, [sp, #32]\n" \
            "ldp x6, x7, [sp, #48]\n" \
            "ldr x8, [sp, #64]\n" \
            "ldr x30, [sp, #72]\n" \
            "add sp, sp, #96\n" \
            "cbz x9, 1f\n" \
            "br x9\n" \
            "1: ret\n"); \
}

MIWEATHER_PROXY_TAIL_ENTRY(app_entry_point, miweather_resolve_app_entry_point)
MIWEATHER_PROXY_TAIL_ENTRY(hy_app_init, miweather_resolve_hy_app_init)
#undef MIWEATHER_PROXY_TAIL_ENTRY
#else
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void app_entry_point() {
    auto entry = reinterpret_cast<void (*)()>(miweather_resolve_app_entry_point());
    if (entry != nullptr) entry();
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void hy_app_init() {
    auto entry = reinterpret_cast<void (*)()>(miweather_resolve_hy_app_init());
    if (entry != nullptr) entry();
}
#endif
