import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    application
}

dependencies {}

application {
    // Define the main class for the application.
    mainClass.set("com.github.vincentfree.app.AppKt")
}

dependencies {
    implementation("com.github.ben-manes.caffeine:caffeine:2.8.8")
}
kotlin {
    sourceSets {
        all {
            languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
        }
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    freeCompilerArgs = listOf("-Xinline-classes")
}