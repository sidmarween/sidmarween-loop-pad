plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.marween.looppad"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.marween.looppad"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("app") {
            storeFile = file("looppad.jks")
            storePassword = "looppad"
            keyAlias = "looppad"
            keyPassword = "looppad"
        }
    }

    buildTypes {
        release {
            // R8: Kotlin-Bibliothek verkleinern und den Code optimieren (Inlining, weniger Prüfungen) –
            // eigener Code bleibt vollständig erhalten (siehe proguard-rules.pro)
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("app")
        }
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // keine Null-Prüfungen an jedem Methodenaufruf (spart Zeit in den Audio-Schleifen)
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
