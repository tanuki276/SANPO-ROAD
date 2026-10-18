plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.sanporoad"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.sanporoad"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("SANPO_VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("SANPO_VERSION_NAME").get()

        buildConfigField("String", "SUPABASE_URL", "\"https://djkolanmyxvqmegqpvlp.supabase.co\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"sb_publishable_BkB1dj8UPD9cxgpfhJT5IA__tEsmfoe\"")
        buildConfigField("String", "SHARE_API_URL", "\"https://sanpo-road-share.vercel.app/api/share\"")
    }
    signingConfigs {
        if (!System.getenv("SANPO_KEYSTORE_PATH").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("SANPO_KEYSTORE_PATH"))
                storePassword = System.getenv("SANPO_STORE_PASSWORD")
                keyAlias = System.getenv("SANPO_KEY_ALIAS")
                keyPassword = System.getenv("SANPO_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // supabase-kt 3.0.1 is compatible with the project's Kotlin 2.0.21 toolchain.
    implementation("io.github.jan-tennert.supabase:auth-kt:3.0.1")
    implementation("io.github.jan-tennert.supabase:postgrest-kt:3.0.1")
    implementation("io.github.jan-tennert.supabase:storage-kt:3.0.1")
    implementation("io.ktor:ktor-client-android:3.0.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
}

