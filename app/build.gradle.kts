import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
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

    // Two distributions of the same app. "standard" is the public one (Play Store) with
    // the internet-based shared map pool. "institution" is for a building's own staff
    // and visitors: nothing leaves the device — no Firebase in the APK, no internet
    // permission, no backup (see src/institution and docs/GIZLILIK.md). It installs
    // next to the public app.
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("institution") {
            dimension = "distribution"
            applicationIdSuffix = ".kurum"
            versionNameSuffix = "-kurum"
        }
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
    "standardImplementation"(project(":cloud-firebase"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.camera.view)
    implementation(libs.google.ar.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
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
// google-services.json configures Firebase, which only the standard build has. The
// institution build's application id isn't in that file, so its task would fail; and
// there's nothing for it to generate anyway.
tasks.configureEach {
    if (name.startsWith("process") && name.endsWith("GoogleServices") && name.contains("Institution")) enabled = false
}

/**
 * Fails the build if an institution APK could reach the network or leave a copy
 * of its data anywhere: an internet permission or allowed backup in the merged
 * manifest, or a Firebase service library on the runtime classpath. Runs with
 * `check`, so CI enforces what docs/GIZLILIK.md promises.
 */
abstract class VerifyInstitutionPrivacy : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val runtimeComponents: SetProperty<String>

    @TaskAction
    fun verify() {
        val manifest = mergedManifest.get().asFile.readText()
        val problems = buildList {
            FORBIDDEN_PERMISSIONS
                .filter { manifest.contains("""android:name="$it"""") }
                .forEach { add("the merged manifest asks for $it") }
            if (!Regex("""android:allowBackup="false"""").containsMatchIn(manifest)) add("backup isn't switched off (android:allowBackup)")
            runtimeComponents.get()
                .filter { component -> FORBIDDEN_LIBRARIES.any { component.startsWith(it) } }
                .forEach { add("$it is on the runtime classpath") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("The institution build must keep everything on the device:\n" + problems.joinToString("\n") { "  - $it" })
        }
    }

    private companion object {
        // Services that talk to Google's servers. ML Kit's own helper libraries
        // (firebase-components, firebase-encoders) don't, and stay.
        val FORBIDDEN_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            // Implied by an old library manifest, never used (see AndroidManifest.xml).
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
        )

        val FORBIDDEN_LIBRARIES = listOf(
            "com.google.firebase:firebase-firestore",
            "com.google.firebase:firebase-auth",
            "com.google.firebase:firebase-database",
            "com.google.firebase:firebase-storage",
            "com.google.firebase:firebase-analytics",
            "com.google.firebase:firebase-crashlytics",
            "com.google.firebase:firebase-messaging",
            "com.google.firebase:firebase-installations",
            "com.google.firebase:firebase-config",
            "com.google.firebase:firebase-perf",
            "com.google.android.gms:play-services-measurement",
        )
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "institution")) { variant ->
        val runtime = variant.runtimeConfiguration
        val verify = tasks.register<VerifyInstitutionPrivacy>("verify${variant.name.replaceFirstChar { it.uppercase() }}Privacy") {
            group = "verification"
            description = "Checks that the ${variant.name} build can't reach the network or back its data up."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            runtimeComponents.set(
                runtime.incoming.resolutionResult.rootComponent.map { root ->
                    val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
                    val pending = ArrayDeque(listOf(root))
                    while (pending.isNotEmpty()) {
                        val component = pending.removeFirst()
                        if (!seen.add(component)) continue
                        component.dependencies
                            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                            .forEach { pending.add(it.selected) }
                    }
                    seen.map { it.id }
                        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
                        .map { "${it.group}:${it.module}" }
                        .toSet()
                },
            )
        }
        tasks.named("check") { dependsOn(verify) }
    }
}
