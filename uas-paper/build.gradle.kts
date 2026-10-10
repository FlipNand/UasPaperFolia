plugins {
    `java-library`
}

// A library, not a plugin: this module has no plugin.yml, so it cannot be installed on a
// server. The installable jar is produced by :uas-example, which shades this module in.
// Paper plugins are not loaded from nested jars, which is why the plugin shades rather than
// declaring this as a runtime dependency.
description = "Paper/Folia integration library: connection-channel injection, Java-side block backends, resource-pack delivery."

// Paper 26.3 is published as a Java 25 artifact (its module metadata declares
// org.gradle.jvm.version = 25), so the modules that compile against it must target
// 25 as well. The reusable engine modules stay on 21: nothing there needs the newer
// bytecode, and a 21 target keeps them loadable from older tooling.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    api(project(":uas-api"))
    api(project(":uas-core"))
    implementation(project(":uas-protocol"))
    implementation(project(":uas-netty"))

    compileOnly(libs.paper.api)
    compileOnly(libs.bundles.netty)
    compileOnly(libs.gson)

    // The channel locator and the per-player work queue are pure logic over Netty
    // types and the scheduler interface, so they are tested here without a server.
    testImplementation(libs.bundles.netty)
    testImplementation(project(":uas-netty"))
    testImplementation(project(":uas-protocol"))
    // compileOnly keeps paper-api out of the shipped jar, but the tests compile against
    // Bukkit types (Player, Location), so the test classpath needs it explicitly.
    testImplementation(libs.paper.api)
}
