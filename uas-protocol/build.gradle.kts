plugins {
    `java-library`
}

description = "Shared binary serialization for UAS control packets."

dependencies {
    api(project(":uas-api"))
}
