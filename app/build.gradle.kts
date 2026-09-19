import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// Release signing credentials never live in this repository — it's public. They come
// from, in order:
//   1. environment variables (for CI): UMAY_KEYSTORE_FILE, UMAY_KEYSTORE_PASSWORD,
//      UMAY_KEY_ALIAS, UMAY_KEY_PASSWORD
//   2. a properties file outside the repo: ~/.umay-signing/keystore.properties by
//      default, or wherever the Gradle property "umay.signing.properties" points
//      (storeFile, storePassword, keyAlias, keyPassword)
// With neither, release builds are simply left unsigned, so anyone can clone and build.
val releaseSigning: Properties? = System.getenv("UMAY_KEYSTORE_FILE")
    ?.let { storeFile ->
        Properties().apply {
            setProperty("storeFile", storeFile)
            setProperty("storePassword", System.getenv("UMAY_KEYSTORE_PASSWORD").orEmpty())
            setProperty("keyAlias", System.getenv("UMAY_KEY_ALIAS").orEmpty())
            setProperty("keyPassword", System.getenv("UMAY_KEY_PASSWORD").orEmpty())
        }
    }
    ?: (findProperty("umay.signing.properties") as String? ?: "${System.getProperty("user.home")}/.umay-signing/keystore.properties")
        .let(::file)
        .takeIf { it.isFile }
        ?.let { propertiesFile -> Properties().apply { propertiesFile.inputStream().use(::load) } }

android {
    namespace = "com.appvalence.umay"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.appvalence.umay"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // For judging performance on a real phone: exactly the release build (R8, not
        // debuggable, baseline profile applied), just signed with the local debug key so
        // Android Studio can install it. Debug builds run Compose several times slower
        // and say nothing about how the released app will feel.
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core-model"))
    implementation(project(":positioning"))
    implementation(project(":perception"))
    implementation(project(":orchestrator"))
    implementation(project(":feedback"))
    implementation(project(":cloud"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}