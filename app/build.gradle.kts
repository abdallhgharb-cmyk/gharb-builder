plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gharb.studiapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gharb.studiapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = project.findProperty("GHARB_KEYSTORE_PATH")?.toString()
                ?: "${rootDir}/keystore/gharb-release.p12"
            val ksFile = file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storeType = "PKCS12"
                storePassword = project.findProperty("GHARB_KEYSTORE_PASS")?.toString() ?: "gharb@2026"
                keyAlias = project.findProperty("GHARB_KEY_ALIAS")?.toString() ?: "gharb_release_key"
                keyPassword = project.findProperty("GHARB_KEY_PASS")?.toString() ?: "gharb@2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
