enableFeaturePreview("NO_IMPLICIT_LOOKUP_IN_PARENT_PROJECTS")
// Required while the scripta composite build is present: its settings enables typesafe project
// accessors, which makes Gradle generate accessors for this build too. A project literally named
// "extensions" would generate getExtensions(), clashing with ExtensionAware.getExtensions()
// (AbstractMethodError) — hence the module lives in extension-packs/.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// The LSParanoid plugin (dev.ujhhgtg.lsparanoid) is deliberately not published to any Maven
// repository, so a machine without it in mavenLocal cannot resolve it. Point this at a source
// checkout to consume it as a composite build instead (CI clones it); when unset, resolution stays
// exactly as before and falls back to mavenLocal.
val lsparanoidCheckout: String? =
    providers.environmentVariable("WEKIT_LSPARANOID_CHECKOUT").orNull?.trim()?.takeIf { it.isNotEmpty() }

pluginManagement {
    // Supplies the dev.ujhhgtg.lsparanoid plugin from the included build instead of the
    // unpublished Maven artifact. Note: this block is compiled in its own scope, so it cannot
    // read a val declared in the settings script body.
    providers.environmentVariable("WEKIT_LSPARANOID_CHECKOUT").orNull
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { includeBuild(it) }
    repositories {
        mavenLocal { content { includeGroup("dev.ujhhgtg.lsparanoid") } }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal { content { includeGroup("dev.ujhhgtg.lsparanoid") } }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.Ujhhgtg")
                includeGroup("com.github.topjohnwu.libsu")
            }
        }
        maven("https://api.xposed.info/") {
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
        mavenCentral()
        val gprUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GITHUB_ACTOR"))
        val gprKey = providers.gradleProperty("gpr.key")
            .orElse(providers.environmentVariable("GITHUB_TOKEN"))
        maven {
            name = "GitHubPackagesMiuix"
            url = uri("https://maven.pkg.github.com/compose-miuix-ui/miuix")
            if (gprUser.isPresent && gprKey.isPresent) {
                credentials {
                    username = gprUser.get()
                    password = gprKey.get()
                }
            }
        }
    }

    versionCatalogs {
        create("libs")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "wekit"

// Composite build: scripta code editor (not published to Maven Central; keep its own
// toolchain, plugins and version catalog).
includeBuild("libs/common/scripta")
// Substitutes dev.ujhhgtg.lsparanoid:core/runtime with the checkout's modules when present.
lsparanoidCheckout?.let { includeBuild(it) }

include(
    ":app",
    ":libs:common:annotation-scanner",
    ":libs:common:stubs",
    ":libs:common:bsh",
    ":libs:common:reflekt",
    ":libs:python-runtime-api",
)
