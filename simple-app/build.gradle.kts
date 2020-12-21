plugins {
    application
}

version = "0.0.1"
val vertxVersion = "4.0.0"

repositories {
    jcenter()
}

application {
    // Define the main class for the application.
    mainClass.set("com.github.vincentfree.app.AppKt")
}
