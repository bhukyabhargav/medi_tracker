plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.services) apply false
}

allprojects {
    val userHome = System.getProperty("user.home")
    layout.buildDirectory.set(file("$userHome/.gradle-builds/MedTracker/${project.name}"))
}
