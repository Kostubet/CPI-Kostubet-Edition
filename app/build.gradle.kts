plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cosmod.launcherglass"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kostubet.cpi"
        minSdk = 31
        targetSdk = 35
        versionCode = 328
        versionName = "0.2.78"
    }

    signingConfigs {
        if (file("debug.keystore").isFile) create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(project(":coui-controls"))
    compileOnly("de.robv.android.xposed:api:82")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    constraints {
        implementation("androidx.activity:activity:1.8.0")
        implementation("androidx.customview:customview:1.1.0")
        implementation("androidx.drawerlayout:drawerlayout:1.1.1")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20")
    }
    testImplementation("junit:junit:4.13.2")
}
