plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alan.shufflebot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alan.shufflebot"
        minSdk = 30          // Android 11+ (necessário para tirar print pelo serviço de acessibilidade)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("fixa") {
            storeFile = file("shufflebot.jks")
            storePassword = "shufflebot"
            keyAlias = "shufflebot"
            keyPassword = "shufflebot"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("fixa") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { events("passed", "failed"); showStandardStreams = true } }
    }
}

dependencies {
    // leitura do nome do inimigo na tela (roda no próprio celular, sem internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
