// Resolved explicitly rather than through the generated `libs` accessor, because
// the generated accessor is not visible inside the `subprojects {}` block.
val catalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

allprojects {
    group = "dev.uas"
    version = "1.0.0"
}

subprojects {
    // Each module applies `java-library` in its own `plugins {}` block (that is
    // what gives the script its type-safe `api` / `implementation` accessors).
    // Everything shared is configured here, once.
    plugins.withId("java-library") {
        extensions.configure<JavaPluginExtension> {
            // The whole engine targets Java 21 bytecode. We deliberately do NOT use
            // a Java toolchain: the build must work on whatever JDK >= 21 is
            // present, and a toolchain would try to auto-provision a JDK download.
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(
                listOf(
                    // Fail loudly on suspicious code instead of guessing at runtime.
                    "-Xlint:all,-serial,-processing,-this-escape",
                    "-parameters",
                ),
            )
            options.isDeprecation = true
        }

        dependencies {
            "testImplementation"(catalog.findLibrary("junit-jupiter").get())
            "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Leak detection is cheap to enable here and turns any pipeline
            // refcount mistake into a hard, actionable test failure.
            systemProperty("io.netty.leakDetection.level", "paranoid")
            systemProperty("io.netty.leakDetection.targetRecords", "10")
            // A watchdog for the whole suite. JUnit's default timeout thread mode is
            // SAME_THREAD, which only *reports* a timeout once the test returns -- so a
            // test that blocks on I/O would hang the build instead of failing. Running
            // timeouts on a separate thread makes an overrun an actual failure.
            systemProperty("junit.jupiter.execution.timeout.default", "60s")
            systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
            maxHeapSize = "1g"
            testLogging {
                events("passed", "skipped", "failed")
                // Test stdout carries the evidence for several verification tests
                // (heap headroom under a forced small heap, allocator geometry, raw
                // protocol dumps), so it must be visible rather than swallowed.
                showStandardStreams = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}
