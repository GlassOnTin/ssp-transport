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

    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
