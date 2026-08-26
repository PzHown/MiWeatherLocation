#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>

namespace {

constexpr const char* kTag = "MiWeatherLocationProxy";
constexpr const char* kOriginalEnv = "MIWEATHERLOCATION_ORIGINAL_BINARY";
constexpr const char* kOriginalName = "libweather_app.so";
constexpr const char* kHyperOsAppPublicLibrary = "libhyper_os_app_public.so";

using HyperOsLaunchMainThread = void (*)();

std::mutex gLoadMutex;
void* gOriginalHandle = nullptr;
std::atomic<bool> gWorkerTriggered{false};

void logLine(int priority, const char* text) {
    __android_log_print(priority, kTag, "%s", text == nullptr ? "" : text);
}

std::string siblingOriginalWeatherPath() {
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(&siblingOriginalWeatherPath), &info) == 0
            || info.dli_fname == nullptr
            || info.dli_fname[0] == '\0') {
        return {};
    }
    std::string selfPath(info.dli_fname);
    size_t slash = selfPath.find_last_of('/');
    if (slash == std::string::npos) return {};
    return selfPath.substr(0, slash + 1) + kOriginalName;
}

std::string resolveOriginalWeatherPath() {
    const char* envPath = std::getenv(kOriginalEnv);
    if (envPath != nullptr && envPath[0] != '\0') {
        return envPath;
    }
    std::string fallback = siblingOriginalWeatherPath();
    if (!fallback.empty()) {
        std::string message = "original binary env missing; using sibling fallback: " + fallback;
        logLine(ANDROID_LOG_WARN, message.c_str());
    }
    return fallback;
}

void* loadOriginalWeatherBinary() {
    std::lock_guard<std::mutex> lock(gLoadMutex);
    if (gOriginalHandle != nullptr) return gOriginalHandle;

    std::string path = resolveOriginalWeatherPath();
    if (path.empty()) {
        logLine(ANDROID_LOG_ERROR, "proxy entered but original Weather binary path is unresolved");
        return nullptr;
    }

    std::string message = "loading original Weather binary: " + path;
    logLine(ANDROID_LOG_INFO, message.c_str());
    dlerror();
    // DPIS' proven HyperOS Rust proxy uses RTLD_GLOBAL. Some HyperOS native
    // components expect symbols from the original app library to be globally visible.
    gOriginalHandle = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (gOriginalHandle == nullptr) {
        const char* error = dlerror();
        std::string failure = std::string("dlopen original Weather binary failed: ")
                + (error == nullptr ? "unknown" : error);
        logLine(ANDROID_LOG_ERROR, failure.c_str());
        return nullptr;
    }

    logLine(ANDROID_LOG_INFO, "original Weather binary loaded with RTLD_GLOBAL");
    return gOriginalHandle;
}

extern "C" jint JNI_OnLoad(JavaVM*, void*);

void triggerDatabaseWorker() {
    bool expected = false;
    if (!gWorkerTriggered.compare_exchange_strong(expected, true)) return;
    // main.cpp's JNI_OnLoad does not dereference the VM pointer; it is also our
    // process-local bootstrap for the database worker.
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

// HyperOS Rust proxy implementations in the wild also forward this helper from
// libhyper_os_app_public.so. Exporting it keeps the sibling proxy compatible with
// spawner/runtime paths that resolve launch_main_thread from the selected binary.
extern "C" [[gnu::visibility("default")]] [[gnu::used]]
void launch_main_thread() {
    dlerror();
    void* handle = dlopen(kHyperOsAppPublicLibrary, RTLD_NOW | RTLD_GLOBAL);
    if (handle == nullptr) {
        handle = dlopen("/system_ext/lib64/libhyper_os_app_public.so", RTLD_NOW | RTLD_GLOBAL);
    }
    const char* openError = dlerror();
    HyperOsLaunchMainThread original = nullptr;
    if (handle != nullptr) {
        dlerror();
        original = reinterpret_cast<HyperOsLaunchMainThread>(dlsym(handle, "launch_main_thread"));
    }
    const char* symbolError = dlerror();
    std::string message = "forward launch_main_thread handle="
            + std::to_string(reinterpret_cast<uintptr_t>(handle))
            + " original=" + std::to_string(reinterpret_cast<uintptr_t>(original))
            + " openError=" + (openError == nullptr ? "" : openError)
            + " symbolError=" + (symbolError == nullptr ? "" : symbolError);
    logLine(original == nullptr ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, message.c_str());
    if (original != nullptr) original();
}
