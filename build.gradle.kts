plugins {
    java
    // paperweight-userdev gives us Paper's Mojang-mapped server internals (NMS) for the
    // one class that must speak Minecraft's own codecs: the Fabric recipe-sync encoder.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
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
        // paper-api and the paperweight dev bundle are published ONLY here.
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Paper 26.2 dev bundle, pinned to a known-good build (same pin as HomeCraftMgmt). It
    // provides paper-api transitively plus the Mojang-mapped server jar. Bump deliberately;
    // anything under com.dierks.craftbridge.jei.nms is the code that may need a touch-up.
    paperweight.paperDevBundle("26.2.build.107-stable")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Paper 1.20.5+ runs Mojang-mapped, so the plain jar is the production artifact (no reobf).
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

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

// Nothing is shaded: the plugin only uses the Paper API, Paper internals and the JDK.
tasks.jar {
    archiveBaseName.set("CraftBridge")
}
