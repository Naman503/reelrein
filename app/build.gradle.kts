import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing. Locally: a git-ignored `keystore.properties` next to settings.gradle.kts
 * (storeFile, storePassword, keyAlias, keyPassword). In CI: the RELEASE_* environment
 * variables, filled from GitHub secrets. Without either, release builds fall back to the
 * debug key so `assembleRelease` still works on any machine (don't publish those).
 */
val releaseSigning: Map<String, String?>? = run {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        val p = Properties().apply { file.inputStream().use { load(it) } }
        mapOf(
            "storeFile" to p.getProperty("storeFile"),
            "storePassword" to p.getProperty("storePassword"),
            "keyAlias" to p.getProperty("keyAlias"),
            "keyPassword" to p.getProperty("keyPassword"),
        )
    } else {
        System.getenv("RELEASE_STORE_FILE")?.let {
            mapOf(
                "storeFile" to it,
                "storePassword" to System.getenv("RELEASE_STORE_PASSWORD"),
                "keyAlias" to System.getenv("RELEASE_KEY_ALIAS"),
                "keyPassword" to System.getenv("RELEASE_KEY_PASSWORD"),
            )
        }
    }
}

android {
    namespace = "com.naman.reelrein"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.naman.reelrein"
        minSdk = 26 // StrokeDescription.continueStroke (the held 2x press) needs API 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile")!!)
                storePassword = releaseSigning["storePassword"]
                keyAlias = releaseSigning["keyAlias"]
                keyPassword = releaseSigning["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// APKs come out as ReelRein-<version>-release.apk
base {
    archivesName.set("ReelRein-${android.defaultConfig.versionName}")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")

    testImplementation("junit:junit:4.13.2")
}
