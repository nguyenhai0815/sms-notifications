plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guxplus.smsnotifications"
    compileSdk = 34

    defaultConfig {
        // Moi lan Actions chay lai tang mot so, de cai de len ban cu khong vuong.
        val build = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

        applicationId = "com.guxplus.smsnotifications"
        minSdk = 24
        targetSdk = 34
        versionCode = build
        versionName = "1.$build"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
