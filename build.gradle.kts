// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Reads app/google-services.json (gitignored — see README) and generates the
    // resources the Firebase SDKs read at runtime. Applied only in :app, where that
    // file lives; :cloud can still use Firebase SDKs as a dependency of :app.
    alias(libs.plugins.google.services) apply false
}