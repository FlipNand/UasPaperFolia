plugins {
    `java-library`
}

description = "Production verification example: animated custom block, custom sounds, cross-platform HUD, packet on interaction."

// Matches :uas-paper: the plugin runs inside a Paper 26.3 server and links against
// its Java 25 API.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":uas-api"))
    implementation(project(":uas-core"))
    implementation(project(":uas-protocol"))
    implementation(project(":uas-netty"))
    implementation(project(":uas-paper"))

    compileOnly(libs.paper.api)
    compileOnly(libs.bundles.netty)
    compileOnly(libs.gson)

    // The tests verify the generated pack by parsing the finished archive, so gson is needed
    // on the test classpath even though it stays compile-only for the shipped jar.
    testImplementation(libs.gson)
}

// A single fat jar that can be dropped into `plugins/`. Paper plugins are not
// auto-loaded from nested jars, so the engine is shaded in rather than declared
// as a dependency.
val runtimeClasspath = configurations.named("runtimeClasspath")

tasks.jar {
    // Shading the runtime classpath means this task reads the other modules' jar outputs, so
    // the dependency has to be declared: without it Gradle's task-ordering validation fails
    // the build, and a parallel build could zip a half-written jar.
    dependsOn(runtimeClasspath)
    inputs.files(runtimeClasspath).withPropertyName("shadedRuntimeClasspath")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to "UAS Example",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(runtimeClasspath.map { cfg -> cfg.map { if (it.isDirectory) it else zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
    }
}
