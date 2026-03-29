plugins {
    `java-library`
    `maven-publish`
}

group = "club.tesseract"
version = "0.1.0-beta"

repositories {
    mavenCentral()
}

dependencies {

    api(libs.minestom)
    api(libs.minestom.polar)
    // fastutil is needed at compile time for polar's API but minestom only provides it at runtime
    compileOnly("it.unimi.dsi:fastutil:8.5.18")

    compileOnly(libs.slf4j.api)

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(libs.logback)
}

java {
    withSourcesJar()
    withJavadocJar()

    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks {
    test {
        useJUnitPlatform()
        workingDir = file("run")
        // Byte Buddy experimental mode for Java 25+ support
        jvmArgs("-Dnet.bytebuddy.experimental=true")
        doFirst {
            workingDir.mkdirs()
        }
    }

    compileJava {
        options.encoding = "UTF-8"
    }

    register<JavaExec>("runTestServer") {
        group = "application"
        mainClass = "club.tesseract.polar.TestMinestomServer"
        classpath = sourceSets["test"].runtimeClasspath
        workingDir = file("run")
        standardInput = System.`in`
    }
}
