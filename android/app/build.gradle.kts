plugins {
    id("com.android.application")
}

android {
    namespace = "com.fluidvoice.remote"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fluidvoice.remote"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
