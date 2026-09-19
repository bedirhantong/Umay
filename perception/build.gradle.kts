plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.appvalence.umay.perception"
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

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    api(project(":core-model"))
    api(libs.androidx.camera.core)
    api(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.tensorflow.lite.task.vision)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
