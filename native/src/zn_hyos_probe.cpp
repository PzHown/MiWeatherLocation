#include "zygisk_next_api.h"

#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>

namespace {

constexpr const char *kTag = "MiWeatherLocationHYOS";
constexpr const char *kTargetPackage = "com.miui.weather2";

void writeProbeLog(const char *process_name, const char *package_name, const char *se_info) {
    char line[1024]{};
    snprintf(line, sizeof(line),
             "ZN HYOS onAppSpecialized package=%s process=%s seinfo=%s pid=%d",
             package_name ? package_name : "",
             process_name ? process_name : "",
             se_info ? se_info : "",
             getpid());
    __android_log_write(ANDROID_LOG_INFO, kTag, line);

    // The callback is post-fork. Keep this probe deliberately tiny: no threads,
    // no C++ allocator-heavy work, no SQLite. A successful file write proves
    // that Zygisk Next 1.5.0 actually delivered the HYOS lifecycle to Weather.
    static constexpr const char *paths[] = {
            "/data/user_de/0/com.miui.weather2/cache/miweatherlocation_zn_hyos.log",
            "/data/user/0/com.miui.weather2/cache/miweatherlocation_zn_hyos.log",
            "/data/data/com.miui.weather2/cache/miweatherlocation_zn_hyos.log",
    };
    for (const char *path : paths) {
        int fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0644);
        if (fd < 0) continue;
        size_t len = strnlen(line, sizeof(line));
        (void)write(fd, line, len);
        (void)write(fd, "\n", 1);
        close(fd);
        break;
    }
}

void onAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (args == nullptr || args->package_name == nullptr || args->process_name == nullptr) return;
    if (strcmp(args->package_name, kTargetPackage) != 0) return;
    writeProbeLog(args->process_name, args->package_name, args->se_info);
}

const ZygiskNextHyosModule kHyosModule = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = onAppSpecialized,
};

void onModuleLoaded(void *, const ZygiskNextAPI *api) {
    if (api == nullptr || api->getRuntime == nullptr) return;
    const ZygiskNextRuntime *runtime = api->getRuntime();
    if (runtime == nullptr || runtime->type != ZN_RUNTIME_HYOS ||
        runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION ||
        runtime->registerModule == nullptr) {
        return;
    }
    int rc = runtime->registerModule(&kHyosModule);
    __android_log_print(rc == ZN_SUCCESS ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        kTag, "register HYOS module rc=%d api=%d", rc, runtime->api_version);
}

}  // namespace

extern "C" __attribute__((visibility("default"), used)) ZygiskNextModule zn_module = {
        .target_api_version = ZYGISK_NEXT_API_VERSION,
        .onModuleLoaded = onModuleLoaded,
};
