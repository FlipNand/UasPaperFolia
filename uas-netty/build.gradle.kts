plugins {
    `java-library`
}

description = "Netty pipeline injection, HTTP-over-Minecraft multiplexing, chunked asset streaming."

dependencies {
    api(project(":uas-api"))
    // The pipeline frame codec is a thin adapter over the shared wire format, so the
    // byte layout has exactly one implementation.
    api(project(":uas-protocol"))

    // Netty is supplied at runtime by the Paper server itself. It is compile-only
    // here so the engine never ships a second Netty and never conflicts with the
    // server's allocator / leak detector configuration.
    compileOnly(libs.bundles.netty)
    compileOnly(libs.gson)

    testImplementation(libs.bundles.netty)
    testImplementation(project(":uas-protocol"))
    testImplementation(libs.gson)
}
