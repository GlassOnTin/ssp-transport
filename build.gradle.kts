plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "mosh-transport"
            pom {
                name.set("mosh-kotlin")
                description.set("Pure Kotlin mosh (Mobile Shell) client transport for Android/JVM")
                url.set("https://github.com/GlassOnTin/mosh-kotlin")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/GlassOnTin/mosh-kotlin")
                }
            }
        }
    }
}
