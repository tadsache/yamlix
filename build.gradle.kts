import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease

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

    // The Marketplace has rejected unsigned plugins since 2021. The key lives
    // only in CI secrets — a signed build is not reproducible on a laptop, and
    // should not be: a certificate that never leaves the release pipeline
    // cannot be leaked from a developer machine.
    //
    // `signPlugin` is skipped when the variables are absent, so `buildPlugin`
    // keeps working for everyone. That means the zip from a local build is
    // unsigned and the Marketplace will not take it, which is correct.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")

        // A pre-release version (0.2.0-beta.1) goes to the channel named by its
        // qualifier; a plain version goes to the default (stable) channel. The
        // channel has to be subscribed to in the IDE, so this is what keeps an
        // "install and see" build off everyone's update check.
        channels = providers.gradleProperty("pluginVersion").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            recommended()

            // The plugin depends on `com.intellij.modules.platform` and bundled
            // YAML, and on nothing else — so the Marketplace will offer it to
            // every JetBrains IDE, not just IDEA. `recommended()` resolves to
            // IDEA Community alone, which makes the compatibility claim on the
            // listing wider than anything that has been checked.
            //
            // These are the IDEs where Ansible repositories are actually opened:
            // Ultimate, PyCharm (the Ansible/Python overlap), and GoLand.
            //
            // `PyCharmProfessional` (PY), not `PyCharmCommunity` (PC): JetBrains
            // merged the two into one PyCharm product, so PC has no 252 release
            // and naming it here matches nothing — silently, which is worse than
            // failing. Check the verified-IDE list in the log when changing this.
            select {
                types = listOf(
                    IntelliJPlatformType.IntellijIdeaUltimate,
                    IntelliJPlatformType.PyCharmProfessional,
                    IntelliJPlatformType.GoLand,
                )
                channels = listOf(ProductRelease.Channel.RELEASE)
                sinceBuild = providers.gradleProperty("pluginSinceBuild")
                untilBuild = providers.gradleProperty("pluginSinceBuild").map { "$it.*" }
            }
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
