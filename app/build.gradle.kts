plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "al.thana.widgets"
    compileSdk = 34
    defaultConfig {
        applicationId = "al.thana.widgets"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
        // THA'NA live (SalesVision AI). Anon key is public by design; access is gated by the PWA session.
        buildConfigField("String", "SUPABASE_URL", "\"https://hrnnlstxepjigtmfhpny.supabase.co\"")
        buildConfigField("String", "ANON_KEY", "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhybm5sc3R4ZXBqaWd0bWZocG55Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzEzMjY1MDcsImV4cCI6MjA4NjkwMjUwN30.abEcQQd4NwB1AnMeyB8ieUt84dnm-Y90scEKOVIhjys\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Compose (versione të qëndrueshme, të fiksuara)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    // Widget-et dhe sinkronizimi
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Teste
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
