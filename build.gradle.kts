plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    kotlin("android") version "2.1.0" apply false
    kotlin("jvm") version "2.1.0" apply false
    // Align the serialization plugin version with your Kotlin version
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
    // From Kotlin 2.0 the Compose compiler ships with Kotlin and is applied as a plugin,
    // replacing composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("org.jetbrains.dokka") version "2.1.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
