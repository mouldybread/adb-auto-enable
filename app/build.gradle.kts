plugins {
    id("com.android.application")
}

// Versioning: versionName is the single source of truth and versionCode is derived from it
// as major*10000 + minor*100 + patch (minor and patch must be <= 99).
//   CI/tag build : -PCI_VERSION_NAME=0.3.5  (passed by build-release.yml from the git tag)
//   CI/dispatch  : -PCI_VERSION_NAME=0.3.5-1-ga07866c (git describe; never published)
//   Local build  : derived from `git describe --tags --match v[0-9]*`, so it matches CI
//   No git/tags  : 0.0.0-dev (versionCode 1)
// A -suffix does not change the versionCode, which comes from the leading MAJOR.MINOR[.PATCH].
// Tag builds can never carry a suffix: build-release.yml rejects suffixed tags.
val semverRegex = Regex("^v?([0-9]+)[.]([0-9]+)(?:[.]([0-9]+))?")
val strictVersionRegex = Regex("^[0-9]+[.][0-9]+([.][0-9]+)?(-[0-9A-Za-z.-]+)?$")

fun gitDescribe(): String? = try {
    providers.exec {
        commandLine("git", "describe", "--tags", "--match", "v[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

val ciVersionName: String? = (project.findProperty("CI_VERSION_NAME") as? String)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
if (ciVersionName != null && !strictVersionRegex.matches(ciVersionName)) {
    throw GradleException("CI_VERSION_NAME '" + ciVersionName + "' must look like MAJOR.MINOR[.PATCH][-suffix]")
}

val resolvedVersionName: String = ciVersionName
    ?: gitDescribe()?.removePrefix("v")
    ?: "0.0.0-dev"

val resolvedVersionCode: Int = run {
    val m = semverRegex.find(resolvedVersionName) ?: return@run 1
    val major = m.groupValues[1].toInt()
    val minor = m.groupValues[2].toInt()
    val patch = m.groupValues[3].ifEmpty { "0" }.toInt()
    if (minor > 99 || patch > 99) {
        throw GradleException("minor and patch must be <= 99 for versionCode scheme: " + resolvedVersionName)
    }
    major * 10000 + minor * 100 + patch
}

android {
    namespace = "com.tpn.adbautoenable"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tpn.adbautoenable"
        minSdk = 26
        targetSdk = 35

        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
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
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
