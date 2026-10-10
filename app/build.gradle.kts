plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.github.hugfeature.hydrationbreak"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.hugfeature.hydrationbreak"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("upgradeVersionCode").orElse("7").get().toInt()
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // CI distribution always uses the durable key; local debug builds remain available.
    signingConfigs {
        getByName("debug") {
            val keyPath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (keyPath != null) {
                storeFile = file(keyPath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                storeType = "PKCS12"
            } else if (System.getenv("GITHUB_ACTIONS") == "true") {
                error("CI signing key is required; refusing an ephemeral debug signature")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
