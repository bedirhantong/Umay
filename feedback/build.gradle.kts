plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.appvalence.umay.feedback"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core-model"))

    testImplementation(libs.kotlin.test)
}
