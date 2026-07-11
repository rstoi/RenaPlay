plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.baita.renaplay"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.baita.renaplay"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // SMB client
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // Media playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-ui-leanback:1.2.1")
    implementation("androidx.media3:media3-datasource:1.2.1")

    // Networking (subtitle providers)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.json:json:20231013")

    // Bundled, up-to-date TLS provider: some Fire OS builds ship a system Conscrypt/BoringSSL
    // that mis-negotiates TLS 1.2/1.3 against modern edge servers. Installing this at startup
    // bypasses the broken system provider entirely.
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Image loading for posters/thumbnails
    implementation("com.squareup.picasso:picasso:2.8")

    // Testes JVM puros (sem Android/emulador) para a lógica de parsing/heurísticas
    testImplementation("junit:junit:4.13.2")
}
