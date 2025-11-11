plugins {
    id("com.android.application")
}

android {
    namespace = "com.tpn.tpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tpn.adbautoenable"
        minSdk = 21
        targetSdk = 34
        versionCode = 7
        versionName = "0.2.6"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // libadb-android - Pure Java ADB with pairing support
    implementation("com.github.MuntashirAkon:libadb-android:1.0.1")

    // Conscrypt for TLS support (required for pairing)
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // BouncyCastle for certificate generation
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
}
