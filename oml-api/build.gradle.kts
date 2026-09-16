plugins {
    id("oml.convention.kotlin-jvm")
    // oml-api is the one module a third-party mod compiles against, so it must exist as a real
    // Maven coordinate (org.ohmyloader:oml-api) rather than only as a project path.
    id("oml.convention.publishing")
}

dependencies {
    // Only the escape hatch Payload.Raw needs it: its parameters are ASM's instruction list and
    // method node. compileOnly ⇒ mods that do not use the escape hatch do not get ASM, and it is
    // provided by the loader at runtime.
    compileOnly(libs.asmTree)
    testImplementation(kotlin("test"))
}