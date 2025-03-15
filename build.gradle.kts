plugins {
    kotlin("jvm") version "2.1.10"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

dependencies {
    val lwjglVersion = "3.3.2"
    val lwjglNatives = "natives-windows"

    implementation("org.lwjgl:lwjgl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-opengl:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-glfw:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-stb:$lwjglVersion")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-stb::$lwjglNatives")

    val javacvVersion = "1.5.8"

    // Основные компоненты JavaCV
    implementation("org.bytedeco:javacv:$javacvVersion") {
        exclude(group = "org.bytedeco", module = "ffmpeg-platform")
        exclude(group = "org.bytedeco", module = "flycapture")
        exclude(group = "org.bytedeco", module = "libdc1394")
        exclude(group = "org.bytedeco", module = "opencv")
        exclude(group = "org.bytedeco", module = "openkinect")
        exclude(group = "org.bytedeco", module = "videoinput")
        exclude(group = "org.bytedeco", module = "artoolkitplus")
        exclude(group = "org.bytedeco", module = "librealsense")
        exclude(group = "org.bytedeco", module = "librealsense2")
        exclude(group = "org.bytedeco", module = "flandmark")
    }

    // Только FFmpeg для Windows x64
    implementation("org.bytedeco:ffmpeg:5.1.2-$javacvVersion")
    runtimeOnly("org.bytedeco:ffmpeg:5.1.2-$javacvVersion:windows-x86_64")
}

application {
    mainClass.set("main.MainKt")
}

kotlin {
    jvmToolchain(17)
}

tasks.shadowJar {
    archiveBaseName.set("h264-decoder")
    archiveVersion.set("1.0.0")
    mergeServiceFiles()

    // Оптимизации для уменьшения размера
    minimize {
        exclude(dependency("org.bytedeco:.*:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}