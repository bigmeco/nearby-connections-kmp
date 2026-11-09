plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "org.bigmeco.nearbylab"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.bigmeco.nearbylab"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.google.android.gms:play-services-nearby:19.5.1")
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because(
                "Google Play Services pulls an old Fragment incompatible with ActivityResult permissions",
            )
        }
    }
}
