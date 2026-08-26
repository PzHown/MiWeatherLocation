plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.pzhown.miweathlocation"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.pzhown.miweathlocation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
}
