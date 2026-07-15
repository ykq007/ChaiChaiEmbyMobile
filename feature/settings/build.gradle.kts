apply(from = rootProject.file("gradle/feature-module.gradle"))

dependencies {
    add("implementation", project(":core:contracts"))
    add("implementation", "androidx.compose.foundation:foundation")
    add("implementation", "androidx.compose.material3:material3")
}
