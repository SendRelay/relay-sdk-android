import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    `java-library`
    // Publishes to Maven Central through the Central Portal (sources, javadoc and signatures included).
    id("com.vanniktech.maven.publish") version "0.34.0"
}

repositories { mavenCentral() }

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Java 11 bytecode: runs on Android 7+ (API 24) and any JVM 11+.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

val conformanceDir = System.getenv("RELAY_CONFORMANCE_DIR") ?: file("../conformance").absolutePath

tasks.test {
    useJUnitPlatform()
    environment("RELAY_CONFORMANCE_DIR", conformanceDir)
    // Fixture changes must re-run the tests, not hit Gradle's up-to-date cache.
    inputs.dir(conformanceDir).optional().withPropertyName("conformance")
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    // Signs only when release credentials are present (CI); local builds and publishToMavenLocal skip it.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates("ng.com.sendrelay", "relay-android", version.toString())
    pom {
        name.set("Relay Android SDK")
        description.set("Official Relay SDK for Android, Kotlin and Java: track a delivery live")
        url.set("https://docs.sendrelay.com.ng/sdks/android-sdk")
        licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
        developers { developer { id.set("sendrelay"); name.set("SendRelay") } }
        scm {
            url.set("https://github.com/SendRelay/relay-sdk-android")
            connection.set("scm:git:https://github.com/SendRelay/relay-sdk-android.git")
        }
    }
}
