rootProject.name = "uas"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.opencollab.dev/main/")
        // Paper 26.3 betas are published to PaperMC's repo; mavenLocal() lets a
        // developer test against a locally built/pinned paper-api without a remote.
        mavenLocal()
    }
}

include(
    ":uas-api",
    ":uas-protocol",
    ":uas-netty",
    ":uas-core",
    ":uas-geyser",
    ":uas-paper",
    ":uas-example",
)
