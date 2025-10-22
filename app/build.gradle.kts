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
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
