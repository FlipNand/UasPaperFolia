plugins {
    `java-library`
}

description = "Declarative, platform-agnostic asset model (blocks, sounds, UI, packs)."

// Intentionally zero runtime dependencies: this module is embedded verbatim in
// both the Paper plugin and the Geyser extension, so it must not drag in NMS,
// Geyser, Netty, or a JSON implementation.
