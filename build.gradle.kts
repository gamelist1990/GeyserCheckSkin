plugins { java }

group = "com.pexserver"
version = "1.1.0"

repositories {
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

dependencies {
    compileOnly("org.geysermc.geyser:core:2.11.3-SNAPSHOT")
    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("org.geysermc.geyser:core:2.11.3-SNAPSHOT")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
tasks.test { useJUnitPlatform() }
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("extension.yml") { expand("version" to project.version) }
}
tasks.jar {
    archiveBaseName.set("GeyserCheckSkin")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from("GEYSER-LICENSE.txt") { into("META-INF/licenses") }
    from("LICENSE") { into("META-INF") }
    from("THIRD_PARTY_NOTICES.md") { into("META-INF") }
}
