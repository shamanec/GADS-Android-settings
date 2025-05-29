plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.gads.settings"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gads.settings"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugLikeRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debugLikeRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation ("org.java-websocket:Java-WebSocket:1.5.3")

    implementation ("io.ktor:ktor-server-core:2.3.4")
    implementation ("io.ktor:ktor-server-cio:2.3.4")

    // JSON support
    implementation ("io.ktor:ktor-server-content-negotiation:2.3.4")
    implementation ("io.ktor:ktor-serialization-kotlinx-json:2.3.4")

    // Kotlinx Serialization
    implementation ("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")
}