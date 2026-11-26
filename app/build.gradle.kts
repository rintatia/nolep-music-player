plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "id.nolep.player"
    compileSdk = 34
    defaultConfig {
        applicationId = "id.nolep.player"
        minSdk = 26
        targetSdk = 34
        versionCode = run
        versionName = rootProject.file("version.txt").readText().trim() + ".$run"
    }
    signingConfigs {
        create("nolep") {
            storeFile = file("nolep.keystore")
            storePassword = "nolep-player"
            keyAlias = "nolep"
            keyPassword = "nolep-player"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nolep")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
