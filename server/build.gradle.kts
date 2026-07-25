plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The wire contract lives in :core, so the server and the watch are compiled against
    // the same Kotlin types. There is no second schema to keep in sync.
    implementation(project(":core"))

    implementation(libs.bundles.ktor.server)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.coroutines.test)
}

application {
    mainClass.set("com.badwatch.server.ApplicationKt")
}

/** Fills the data directory with synthetic sessions so the dashboard can be reviewed locally. */
tasks.register<JavaExec>("seedDemoData") {
    group = "application"
    description = "Writes synthetic sessions into the dashboard data directory (development only)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.badwatch.server.SyntheticSessionsKt")
    args(providers.gradleProperty("dataDir").getOrElse("badwatch-data"))
}

tasks.test {
    useJUnit()
}
