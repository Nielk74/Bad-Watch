plugins {
    id("com.android.application") version "8.4.0" apply false
    id("com.android.library") version "8.4.0" apply false
    kotlin("android") version "1.9.23" apply false
    kotlin("jvm") version "1.9.23" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
    id("org.jetbrains.dokka") version "1.9.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
