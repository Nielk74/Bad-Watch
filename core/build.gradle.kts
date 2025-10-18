plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.dokka")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.kotlin.serialization.json)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.coroutines.core)
}

tasks.test {
    useJUnit()
}
