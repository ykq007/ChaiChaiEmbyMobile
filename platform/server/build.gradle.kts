plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.chaichai.mobile.platform.server"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":platform:proxy"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.3.2")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.3.2")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.84")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.work:work-testing:2.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
