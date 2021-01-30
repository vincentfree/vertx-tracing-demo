plugins {
    kotlin("jvm") version "1.4.21"
}
repositories {
    jcenter()
}
allprojects {
    repositories {
        jcenter()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    dependencies {
        // Align versions of all Kotlin components
        implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.4.21"))

        // Use the Kotlin JDK 8 standard library.
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

        // Use JUnit Jupiter API for testing.
        testImplementation("org.junit.jupiter:junit-jupiter-api:5.7.0")

        // Use JUnit Jupiter Engine for testing.
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}


subprojects {
    repositories {
        jcenter()
    }
    apply(plugin = "org.jetbrains.kotlin.jvm")
    val vertxVersion = "4.0.0"
    dependencies {
        implementation(platform("io.vertx:vertx-stack-depchain:$vertxVersion"))
        implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version = "1.4.2")
        implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-jdk8", version = "1.4.2")
        implementation(group = "io.vertx", name = "vertx-core")
        implementation(group = "io.vertx", name = "vertx-web")
        implementation(group = "io.vertx", name = "vertx-web-client")
        implementation(group = "io.vertx", name = "vertx-lang-kotlin")
        implementation(group = "io.vertx", name = "vertx-lang-kotlin-coroutines")
        implementation(group = "io.vertx", name = "vertx-config")
        implementation(group = "io.vertx", name = "vertx-opentracing")
        implementation(group = "org.apache.logging.log4j", name = "log4j-api", version = "2.14.0")
        implementation(group = "org.apache.logging.log4j", name = "log4j-core", version = "2.14.0")
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "11"
        }
    }
}
