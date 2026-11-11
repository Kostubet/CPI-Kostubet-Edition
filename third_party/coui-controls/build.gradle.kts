plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.suqi8.coui.controls"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api("androidx.compose.foundation:foundation:1.9.3")
    api("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.annotation:annotation:1.9.1")
}
