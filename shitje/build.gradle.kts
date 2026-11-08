plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "al.thana.shitje"
    compileSdk = 34
    defaultConfig {
        applicationId = "al.thana.shitje"   // aplikacion i ri, i ndarë nga «THA'NA» (al.thana.widgets): instalohet krah tij
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        getByName("debug") {
            System.getenv("THANA_KS")?.let { ks ->
                storeFile = file(ks); storePassword = System.getenv("THANA_KS_PASS"); keyAlias = "thana"; keyPassword = System.getenv("THANA_KS_PASS")
            }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
}
