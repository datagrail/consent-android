plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("org.jlleitschuh.gradle.ktlint") version "11.6.1"
}

val libraryVersion = "1.7.0"

// consent-schema version this SDK's models are written against: the `package` of the dgapp
// consent_schema config.proto vendored under consent_schema/ (provenance in consent_schema/SOURCE).
// Exactly one vendored version is expected; once the SDK supports several, name the reported one
// explicitly here. Keep README "Schema Compatibility" in sync.
val consentSchemaVersion: String =
    run {
        val bumpHint = "bump the vendored proto and SCHEMA_VERSION together"
        val protos =
            rootProject.fileTree("consent_schema/proto/datagrail/consent") { include("*/config.proto") }.files
        require(protos.size == 1) {
            "Expected exactly one vendored consent_schema config.proto, found: $protos; $bumpHint"
        }
        val proto = protos.single()
        val pkg =
            Regex("""^package datagrail\.consent\.(v[1-9][0-9]*);""", RegexOption.MULTILINE)
                .find(proto.readText())
                ?.groupValues
                ?.get(1)
                ?: error("No `package datagrail.consent.vN;` in $proto; $bumpHint")
        require(pkg == proto.parentFile.name) {
            "$proto declares $pkg but lives under ${proto.parentFile.name}; $bumpHint"
        }
        pkg
    }

android {
    namespace = "com.datagrail.consent"
    compileSdk = 34

    defaultConfig {
        minSdk = 23
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "LIBRARY_VERSION", "\"$libraryVersion\"")
        buildConfigField("String", "SCHEMA_VERSION", "\"$consentSchemaVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Return default values (0/null/false) for unmocked Android framework calls
            // (e.g. android.util.Log) instead of throwing, so JUnit unit tests can exercise
            // code paths that touch the framework.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.security:security-crypto:1.0.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()

    coordinates("io.datagrail", "consent", libraryVersion)

    pom {
        name.set("DataGrail Consent SDK")
        description.set("Native Android SDK for consent banner display and privacy preference management.")
        url.set("https://github.com/datagrail/consent-android")
        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set("datagrail")
                name.set("DataGrail")
                email.set("support@datagrail.com")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/datagrail/consent-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/datagrail/consent-android.git")
            url.set("https://github.com/datagrail/consent-android")
        }
    }
}

ktlint {
    version.set("1.0.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}
