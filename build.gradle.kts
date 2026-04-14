plugins {
    kotlin("jvm") version "2.3.20"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

repositories {
    mavenCentral()
}

dependencies {
    val lwjglVersion = "3.4.1"
    val lwjglNatives = "natives-windows"

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-openal:$lwjglVersion")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-openal::$lwjglNatives")

    val ffmpegPresetVersion = "8.0.1-1.5.13"

    implementation("org.bytedeco:ffmpeg:$ffmpegPresetVersion")
    runtimeOnly("org.bytedeco:ffmpeg:$ffmpegPresetVersion:windows-x86_64")

    testImplementation(kotlin("test-junit5"))
}

application {
    mainClass.set("main.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveBaseName.set("decoder")
    archiveVersion.set("1.0.0")
    archiveClassifier.set("")
    mergeServiceFiles()

    // Оптимизации для уменьшения размера
    minimize {
        exclude(dependency("org.bytedeco:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
