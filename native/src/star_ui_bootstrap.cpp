// Compile the existing star UI implementation in this translation unit so this
// bootstrap can access its anonymous-namespace worker/state without widening
// their visibility.
#include "star_ui.cpp"

namespace {

void delayedStarBootstrap() {
    // LSPosed can load the native module a little before HYOS has finalized the
    // child's cmdline/process identity. The original constructor therefore may
    // see a non-target process and return. Retry briefly until specialization
    // exposes com.miui.weather2, then start the same worker exactly once.
    for (int attempt = 1; attempt <= 80; ++attempt) {
        if (isTargetProcess()) {
            bool expected = false;
            if (gThreadStarted.compare_exchange_strong(expected, true)) {
                logLine(ANDROID_LOG_INFO,
                        "STAR_BUTTON_BOOTSTRAP_TARGET_READY attempt=%d exe=%s process=%s",
                        attempt, executablePath().c_str(), readSmallFile("/proc/self/cmdline").c_str());
                std::thread(starWorker).detach();
            } else {
                logLine(ANDROID_LOG_INFO,
                        "STAR_BUTTON_BOOTSTRAP_ALREADY_STARTED attempt=%d", attempt);
            }
            return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(125));
    }

    logLine(ANDROID_LOG_WARN,
            "STAR_BUTTON_BOOTSTRAP_TIMEOUT exe=%s process=%s",
            executablePath().c_str(), readSmallFile("/proc/self/cmdline").c_str());
}

__attribute__((constructor)) void startDelayedStarBootstrap() {
    // Do not gate thread creation on isTargetProcess() here. That check is the
    // race this bootstrap is specifically fixing.
    std::thread(delayedStarBootstrap).detach();
}

}  // namespace
