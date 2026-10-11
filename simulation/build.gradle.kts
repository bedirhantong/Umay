plugins {
    alias(libs.plugins.android.library)
}

// Tooling, never shipped: a simulated building and walker that drive the real
// positioning filter and navigation logic, and the page that plays a walk back
// (docs/YOL_HARITASI.md 0.8, 0.11). Its tests are scenario regressions.
android {
    namespace = "com.appvalence.umay.simulation"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":orchestrator"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
