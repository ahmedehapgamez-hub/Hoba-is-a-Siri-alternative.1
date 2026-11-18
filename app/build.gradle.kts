plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hoba.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hoba.assistant"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // مفتاح توقيع ثابت: عشان كل نسخة جديدة تتثبت فوق القديمة من غير ما تمسحها
    signingConfigs {
        create("hoba") {
            storeFile = file("hoba-debug.keystore")
            storePassword = "android"
            keyAlias = "hoba"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("hoba")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
