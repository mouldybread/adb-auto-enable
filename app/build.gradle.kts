plugins {
    id("com.android.application")
}

android {
    namespace = "com.tpn.adbautoenable"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tpn.adbautoenable"
        minSdk = 26
        targetSdk = 35

        // Dynamically accept CI properties or fall back to local defaults
        versionCode = (project.findProperty("CI_VERSION_CODE") as? String)?.toIntOrNull() ?: 12
        versionName = project.findProperty("CI_VERSION_NAME") as? String ?: "0.3.3"
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
    implementation(libs.nanohttpd)
    implementation(libs.libadb)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.androidx.annotation)
}