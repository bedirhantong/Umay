plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// The institution map server (docs/SUNUCU.md): runs on the institution's own
// network, on the JDK's built-in HTTP server — nothing else to install or trust.
dependencies {
    implementation(project(":core-model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.appvalence.umay.server.MainKt")
    applicationName = "umay-server"
}

tasks.test {
    useJUnitPlatform()
}
