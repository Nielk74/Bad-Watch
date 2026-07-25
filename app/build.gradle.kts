import java.util.Locale

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val semanticVersion: String = rootProject.file("VERSION.md")
    .takeIf { it.exists() }
    ?.readText()
    ?.trim()
    ?.ifEmpty { null }
    ?: "0.1.0"

fun computeVersionCode(version: String): Int {
    val parts = version.split(".")
        .map { it.filter { c -> c.isDigit() } }
        .mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

android {
    namespace = "com.badwatch.app"
    // Compiling against 36 is required by current Compose/Wear artifacts. targetSdk stays
    // at 34 deliberately — raising it changes runtime behaviour and needs its own testing pass.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.badwatch.badwatch"
        minSdk = 30
        targetSdk = 34
        versionCode = computeVersionCode(semanticVersion)
        versionName = semanticVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        // These check whether newer dependency versions exist upstream, so they start
        // failing the moment anything is released — turning CI red for reasons unrelated to
        // the commit. Dependency freshness is a deliberate maintenance task, not a build gate.
        disable += setOf(
            "AndroidGradlePluginVersion",
            "GradleDependency",
            "NewerVersionAvailable"
        )
    }
}

dependencies {

    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.wear)
    constraints {
        // androidx.wear:wear pulls an ancient fragment transitively; lint's
        // InvalidFragmentVersionForActivityResult gate needs >= 1.3.0 resolved.
        implementation(libs.androidx.fragment)
    }
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.wear.compose)

    // Watch-face tile. tiles only exposes concurrent-futures on its runtime classpath, but the
    // tile service compiles CallbackToFutureAdapter against it, so it is declared explicitly.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.protolayout.material)
    implementation(libs.androidx.concurrent.futures)

    testImplementation(libs.junit)
    testImplementation(libs.google.truth)
    testImplementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.coroutines.test)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
