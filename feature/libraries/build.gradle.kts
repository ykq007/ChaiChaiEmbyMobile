apply(from = rootProject.file("gradle/feature-module.gradle"))

dependencies {
    add("implementation", project(":core:contracts"))
    add("implementation", "androidx.compose.foundation:foundation")
    add("implementation", "androidx.compose.material3:material3")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    add("testImplementation", "junit:junit:4.13.2")
}
