plugins {
    `java-library`
}

description = "Geyser extension plus just-in-time Java -> Bedrock block, sound and UI translation."

dependencies {
    api(project(":uas-api"))
    implementation(project(":uas-protocol"))
    implementation(project(":uas-core"))

    // Provided by the running Geyser instance. compileOnly so we can never ship a
    // stale copy of Geyser's API or its shaded Cumulus/event-bus transitives.
    compileOnly(libs.geyser.api)
    compileOnly(libs.gson)

    // The JIT translators are deliberately pure (no Geyser types), so they are
    // unit-testable without a Geyser runtime.
    testImplementation(libs.gson)
}
