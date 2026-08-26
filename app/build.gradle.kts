plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.pzhown.miweathlocation"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.pzhown.miweathlocation"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.0-native-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
