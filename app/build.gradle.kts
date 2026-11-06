plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.caio.navi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.caio.navi"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Chave de assinatura fixa, para que cada nova versão instale por cima da anterior.
    signingConfigs {
        create("navi") {
            storeFile = file("navi.keystore")
            storePassword = "navi1234"
            keyAlias = "navi"
            keyPassword = "navi1234"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("navi")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("navi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Reconhecimento de voz offline (sem conta, sem chave, tudo no celular)
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
