plugins {
    `java-library`
}

description = "Pack compilation, constrained-RAM profiles, off-heap asset sources, block backend SPI, UI manager."

dependencies {
    api(project(":uas-api"))
    implementation(project(":uas-protocol"))
    implementation(project(":uas-netty"))
    implementation(libs.gson)

    compileOnly(libs.bundles.netty)

    testImplementation(libs.bundles.netty)
    testImplementation(libs.gson)
}
