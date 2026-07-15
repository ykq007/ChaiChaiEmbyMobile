plugins { id("com.android.library"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.chaichai.mobile.feature.libraries"; compileSdk = 37
    defaultConfig { minSdk = 26 }; buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":design:system"))
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00"); implementation(composeBom)
    implementation("androidx.compose.ui:ui")
}
