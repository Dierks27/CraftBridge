plugins {
    java
}

group = "com.dierks.craftbridge"
version = "0.1.0"
description = "CraftBridge — chest sorting, admin recipes, Linked Workbench and JEI recipe transfer for Paper 26.2"

// Target server: Paper 26.2 on Java 25. The toolchain pins the bytecode level to the
// live server. Gradle 9.7 itself runs fine on JDK 25, so CI needs a single JDK.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven {
        // paper-api is published ONLY here (not on Maven Central).
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Paper 26.2 API, pinned to a known-good build (same pin as HomeCraftMgmt) so a
    // corrupt "newest" build cannot take CI down. Bump deliberately.
    compileOnly("io.papermc.paper:paper-api:26.2.build.107-stable")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.test {
    useJUnitPlatform()
}

// Nothing is shaded: the plugin only uses the Paper API and the JDK.
tasks.jar {
    archiveBaseName.set("CraftBridge")
}
