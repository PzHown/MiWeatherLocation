#include "zygisk_next_api.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace {

constexpr const char *kTag = "MiWeatherLocationHYOS";
constexpr const char *kTargetPackage = "com.miui.weather2";
constexpr const char *kStatusPath = "/data/adb/modules/miweatherlocation_hyos/status.log";

int gStatusFd = -1;

void logOnly(const char *fmt, ...) {
    char line[1536]{};
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    __android_log_write(ANDROID_LOG_INFO, kTag, line);
}

void statusLine(const char *fmt, ...) {
    char line[1536]{};
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);

    __android_log_write(ANDROID_LOG_INFO, kTag, line);
    if (gStatusFd >= 0) {
        size_t len = strnlen(line, sizeof(line));
        (void)write(gStatusFd, line, len);
        (void)write(gStatusFd, "\n", 1);
        (void)fsync(gStatusFd);
    }
}

__attribute__((constructor)) void libraryConstructor() {
    // This runs as soon as the library itself is dlopen()'d. It is deliberately
    // logcat-only so SELinux restrictions on /data/adb cannot hide the result.
    logOnly("S4 constructor reached pid=%d uid=%d", getpid(), getuid());
}

void onAppSpecialized(const ZnHyosAppSpecializeArgs *args) {
    if (args == nullptr) {
        statusLine("S4 callback args=null pid=%d uid=%d", getpid(), getuid());
        return;
    }

    statusLine("S4 callback package=%s process=%s seinfo=%s pid=%d uid=%d",
               args->package_name ? args->package_name : "<null>",
               args->process_name ? args->process_name : "<null>",
               args->se_info ? args->se_info : "<null>",
               getpid(), getuid());

    if (args->package_name != nullptr && strcmp(args->package_name, kTargetPackage) == 0) {
        statusLine("S4 WEATHER_MATCH pid=%d uid=%d", getpid(), getuid());
    }
}

const ZygiskNextHyosModule kHyosModule = {
        .target_api_version = ZYGISK_NEXT_HYOS_API_VERSION,
        .onAppSpecialized = onAppSpecialized,
};

void onModuleLoaded(void *, const ZygiskNextAPI *api) {
    // Log before any filesystem operation. A missing status.log therefore no
    // longer implies that Zygisk Next skipped this callback.
    logOnly("S4 onModuleLoaded ENTER pid=%d uid=%d api=%p", getpid(), getuid(), api);

    gStatusFd = open(kStatusPath, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    int openErrno = gStatusFd < 0 ? errno : 0;
    logOnly("S4 status open fd=%d errno=%d", gStatusFd, openErrno);
    statusLine("S4 onModuleLoaded pid=%d uid=%d fd=%d errno=%d", getpid(), getuid(), gStatusFd, openErrno);

    if (api == nullptr) {
        statusLine("S4 api=null");
        return;
    }
    if (api->getRuntime == nullptr) {
        statusLine("S4 getRuntime=null");
        return;
    }

    const ZygiskNextRuntime *runtime = api->getRuntime();
    if (runtime == nullptr) {
        statusLine("S4 runtime=null");
        return;
    }

    statusLine("S4 runtime type=%d api=%d register=%p", static_cast<int>(runtime->type),
               runtime->api_version, reinterpret_cast<void *>(runtime->registerModule));

    if (runtime->type != ZN_RUNTIME_HYOS) {
        statusLine("S4 runtime_type_mismatch expected=%d actual=%d", static_cast<int>(ZN_RUNTIME_HYOS),
                   static_cast<int>(runtime->type));
        return;
    }
    if (runtime->api_version < ZYGISK_NEXT_HYOS_API_VERSION) {
        statusLine("S4 runtime_api_too_old required=%d actual=%d", ZYGISK_NEXT_HYOS_API_VERSION,
                   runtime->api_version);
        return;
    }
    if (runtime->registerModule == nullptr) {
        statusLine("S4 registerModule=null");
        return;
    }

    int rc = runtime->registerModule(&kHyosModule);
    statusLine("S4 registerModule rc=%d target_api=%d", rc, ZYGISK_NEXT_HYOS_API_VERSION);
}

}  // namespace

extern "C" __attribute__((visibility("default"), used)) ZygiskNextModule zn_module = {
        .target_api_version = ZYGISK_NEXT_API_VERSION,
        .onModuleLoaded = onModuleLoaded,
};
