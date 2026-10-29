plugins { id("com.android.application") }
android {
    namespace = "app.arioso.audiobook"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.arioso.audiobook"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.1.0-preview.39"
        testInstrumentationRunner = providers.gradleProperty("ariosoTestRunner")
            .getOrElse("app.arioso.audiobook.BrowserSmokeInstrumentation")
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { testImplementation("junit:junit:4.13.2") }
