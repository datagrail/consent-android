plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.datagrail.consent.demo"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.datagrail.consent.demo"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Android TV demo endpoints. Override per-developer (do NOT commit your host):
        //   - gradle.properties / ~/.gradle/gradle.properties / local.properties, or
        //   - -PdgTvHost=https://your-host[:port] -PdgTvPublicHost=https://your-host
        // dgTvHost  = base the SDK uses for config + TV polling (emulator: add the
        //             adb-reverse port, e.g. https://your-host:9443)
        // dgTvPublicHost = base encoded in the QR for the phone (usually :443)
        val tvHost = (project.findProperty("dgTvHost") as String?)
            ?: "https://consent-test.example.com"
        val tvPublicHost = (project.findProperty("dgTvPublicHost") as String?) ?: tvHost
        val tvApiKey = (project.findProperty("dgTvApiKey") as String?) ?: "dg_test_readkey"
        buildConfigField("String", "TV_HOST", "\"$tvHost\"")
        buildConfigField("String", "TV_PUBLIC_HOST", "\"$tvPublicHost\"")
        buildConfigField("String", "TV_API_KEY", "\"$tvApiKey\"")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":library"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
