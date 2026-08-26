plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.pzhown.miweathlocation"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.pzhown.miweathlocation"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.4.0-rustprocess-proxy-alpha"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // hyos_spawner loads the proxy before Weather's own libraries,
                // so avoid a dependency on a separately discoverable libc++_shared.so.
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("io.github.libxposed:service:102.0.0")
}
