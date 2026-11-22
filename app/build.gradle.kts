plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("app.cash.licensee")
}

android {
    namespace = "com.dirkraft.macroman"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dirkraft.macroman"
        // Pixel 8 only: ships with Android 14 (API 34)
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
        compose = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/*.md", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties","META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/versions/**", "META-INF/*.kotlin_module")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.anthropic:anthropic-java:2.34.0")

    // The SDK pulls in Jackson 2.18.2 and HttpComponents 5.2/5.3, which have published advisories (DoS and
    // validator bypasses). Pin patched releases; Jackson stays on the 2.18 line the SDK was built against.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.12"))
    constraints {
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.5")
        implementation("org.apache.httpcomponents.core5:httpcore5:5.4.4")
        implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.4")
    }
}

// Fails the build if any dependency, direct or transitive, has a license outside this list.
// Run `./gradlew licensee` for the report (app/build/reports/licensee/).
licensee {
    allow("Apache-2.0")
    allow("MIT")
}
