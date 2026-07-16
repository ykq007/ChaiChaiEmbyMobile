apply(from = rootProject.file("gradle/feature-module.gradle"))

dependencies {
    add("implementation", project(":core:contracts"))
    add("implementation", project(":platform:adaptive"))
    add("implementation", project(":design:system"))
    add("implementation", "androidx.compose.foundation:foundation")
    add("implementation", "androidx.compose.material3:material3")
    add("implementation", "androidx.compose.material:material-icons-extended")
    add("implementation", "androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    add("implementation", "androidx.activity:activity-compose:1.12.1")
}
