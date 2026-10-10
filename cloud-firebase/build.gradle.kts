plugins {
    alias(libs.plugins.android.library)
}

// The shared map pool on Firebase (Firestore + anonymous auth), split out of :cloud
// so the institution build (see :app's "distribution" flavors) can leave Firebase out
// of the APK entirely. Like :cloud before it, this doesn't apply the google-services
// plugin: the Firebase SDKs read the resources :app generates from its
// google-services.json.
android {
    namespace = "com.appvalence.umay.cloud.firebase"
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
    api(project(":cloud"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
}
