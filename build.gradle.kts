plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "bot"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.dv8tion:JDA:5.2.1") {
        exclude(module = "opus-java")
    }
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.61.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("bot.MainKt")
}

// `./gradlew run` 실행 시 프로젝트 루트의 .env 파일을 읽어 환경변수로 주입한다.
// .env 는 git 에 올리지 않는다(토큰 같은 비밀값 포함).
tasks.named<JavaExec>("run") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim().trim('"'))
            }
    }
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("stockbot.jar")
    manifest {
        attributes["Main-Class"] = "bot.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}