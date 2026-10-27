plugins {
    java
    id("com.azuredoom.hytale-tools") version "1.+"
}

group = project.property("group").toString()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(property("java_version").toString().toInt()))
}

hytaleTools {
    javaVersion = property("java_version").toString().toInt()
    hytaleVersion = property("hytale_version").toString()
    manifestServerVersion = property("manifestServerVersion").toString()
    manifestGroup = property("manifest_group").toString()
    modId = property("mod_id").toString()
    modDescription = property("mod_description").toString()
    modUrl = property("mod_url").toString()
    mainClass = property("main_class").toString()
    modCredits = property("mod_author").toString()
    manifestDependencies = property("manifest_dependencies").toString()
    manifestOptionalDependencies = property("manifest_opt_dependencies").toString()
    curseforgeId = property("curseforgeID").toString()
    disabledByDefault = property("disabled_by_default").toString().toBoolean()
    includesPack = property("includes_pack").toString().toBoolean()
    patchline = property("patchline").toString()
    injectServerJavadocsIntoSources = property("injectServerJavadocsIntoSources").toString().toBoolean()
    generateAssetsBinary = property("generateAssetsBinary").toString().toBoolean()
    // Local Hytale assets (set hytaleHomeOverride in ~/.gradle/gradle.properties). Ignored if the path does not exist.
    property("hytaleHomeOverride").toString().takeIf { file(it).exists() }?.let { hytaleHomeOverride = it }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(project.property("mod_name").toString())
    archiveVersion.set(project.property("version").toString())
}

// Offline checks for the schematic readers and block mapper (no server needed).
val compileChecks by tasks.registering(JavaCompile::class) {
    dependsOn(tasks.compileJava)
    source(fileTree("src/check/java"))
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get()
    destinationDirectory.set(layout.buildDirectory.dir("classes/checks"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
val testSchematics by tasks.registering(JavaExec::class) {
    dependsOn(compileChecks)
    classpath = files(compileChecks.flatMap { it.destinationDirectory }, sourceSets.main.get().output,
        configurations.compileClasspath)
    mainClass.set("com.mc2hytale.SchematicCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
}
tasks.check { dependsOn(testSchematics) }
