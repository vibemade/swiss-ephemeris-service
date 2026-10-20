// SPDX-License-Identifier: AGPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    application
}

group = "me.lagna"
// Releases pass -PreleaseVersion=<x.y.z>; the jar manifest carries it and GetInfo reports it.
version = (findProperty("releaseVersion") as String?) ?: "0.2.2-SNAPSHOT"

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.services)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.caffeine)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.grpc.inprocess)
    testRuntimeOnly(libs.junit.launcher)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}" }
        create("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins { create("kotlin") }
        }
    }
}

// libswe, swetest and the .se1 files, built from the pinned sources in native/ (needs a C compiler and curl).
val nativeDir = layout.buildDirectory.dir("native")
val buildLibswe =
    tasks.register<Exec>("buildLibswe") {
        group = "build"
        description = "Builds libswe and swetest and copies the ephemeris files into build/native."
        inputs.files("native/build-libswe.sh", "native/swisseph.lock", "native/swisseph.sha256")
        outputs.dirs(nativeDir.map { it.dir("lib") }, nativeDir.map { it.dir("bin") }, nativeDir.map { it.dir("ephe") })
        commandLine("sh", "native/build-libswe.sh", nativeDir.get().asFile.absolutePath)
    }

val libraryName = if (System.getProperty("os.name").startsWith("Mac")) "libswe.dylib" else "libswe.so"

fun JavaForkOptions.useLibswe() {
    systemProperty("swe.lib", nativeDir.get().file("lib/$libraryName").asFile.absolutePath)
    systemProperty("swe.ephe", nativeDir.get().dir("ephe").asFile.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    dependsOn(buildLibswe)
    useLibswe()
    systemProperty("swe.swetest", nativeDir.get().file("bin/swetest").asFile.absolutePath)
    // On macOS libswe keeps its state process-wide, so test classes must not share a JVM.
    if (System.getProperty("os.name").startsWith("Mac")) forkEvery = 1
}

tasks.test {
    useJUnitPlatform { excludeTags("perf") }
}

// Timing tests, kept out of `check`; results go to build/perf/results.txt.
tasks.register<Test>("perfTest") {
    group = "verification"
    description = "Runs the tests tagged perf and writes their timings to build/perf/results.txt."
    val testSources = sourceSets["test"]
    testClassesDirs = testSources.output.classesDirs
    classpath = testSources.runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    systemProperty("perf.out", layout.buildDirectory.file("perf/results.txt").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

tasks.jar {
    manifest { attributes("Implementation-Title" to project.name, "Implementation-Version" to project.version) }
}

application {
    mainClass.set("me.lagna.ephemeris.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    dependsOn(buildLibswe)
    useLibswe()
}
