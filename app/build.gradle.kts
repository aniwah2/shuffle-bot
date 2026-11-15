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
}
