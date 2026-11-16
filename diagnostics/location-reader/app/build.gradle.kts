plugins { id("com.android.application") }
android {
    namespace = "lab.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "lab.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5-diagnostics"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
