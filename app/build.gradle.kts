plugins { id("com.android.application") }
android {
    namespace = "app.dart.audiobook"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.dart.audiobook"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "0.1.0-preview.25"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
