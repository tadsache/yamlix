import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            providers.gradleProperty("platformType"),
            providers.gradleProperty("platformVersion"),
        )

        // We reuse the platform's YAML PSI. We never define a FileType.
        bundledPlugin("org.jetbrains.plugins.yaml")

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    test {
        useJUnit()
        systemProperty("idea.home.path", "")
    }

    // Rebuilding while the sandbox runs must not half-break it.
    //
    // The sandbox defaults to hot-reloading the plugin whenever its jar
    // changes, but this plugin is not unload-safe — the IDE says so itself:
    // "class loader cannot be unloaded". The reload then leaves a live
    // classloader that cannot find classes which are still present in the jar,
    // and the symptom is a NoClassDefFoundError on every Ctrl-hover rather than
    // anything that points at the cause. A rebuild now leaves the running
    // sandbox alone; restart it to pick changes up.
    runIde {
        systemProperty("idea.auto.reload.plugins", "false")

        // `-PideProject=/path/to/project` opens that project on start, which is
        // how the plugin gets tried against a real repository rather than the
        // fixture. Without it the sandbox reopens whatever it had last.
        (project.findProperty("ideProject") as String?)?.let { args(it) }
    }
}
