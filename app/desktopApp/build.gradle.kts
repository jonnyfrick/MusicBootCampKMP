import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":app:shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "io.github.jonnyfrick.musicbootcamp.desktop.MainKt"
        // GervillSynth renders Java's synthesizer itself (com.sun.media.sound.AudioSynthesizer).
        jvmArgs += listOf("--add-exports", "java.desktop/com.sun.media.sound=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "MusicBootCamp"
            packageVersion = "1.0.0"
            // javax.sound.midi lives in the java.desktop module
            modules("java.desktop")
        }
    }
}

// `./gradlew :app:desktopApp:run -Pmusicbootcamp.dataDir=/some/dir` keeps test runs away from the real data.
val dataDirOverride = providers.gradleProperty("musicbootcamp.dataDir")
tasks.withType<JavaExec>().configureEach {
    dataDirOverride.orNull?.let { systemProperty("musicbootcamp.dataDir", it) }
    // Runs from Gradle are debug runs with the developer tools (Java import, recognition tuning);
    // the packaged app has none. `-Pmusicbootcamp.debug=false` shows it as users get it.
    systemProperty("musicbootcamp.debug", providers.gradleProperty("musicbootcamp.debug").getOrElse("true"))
}
