pluginManagement {
    repositories {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // sherpa-onnx publishes its Android AAR on GitHub releases and not to Maven Central, so
        // it is resolved as an ivy artifact instead. That keeps a 46 MB binary out of the
        // repository while still letting Gradle cache it like any other dependency. Its SHA-256
        // is pinned in gradle/verification-metadata.xml, so a bump needs the new hash there.
        ivy("https://github.com/k2-fsa/sherpa-onnx/releases/download") {
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeGroup("com.k2fsa.sherpa.onnx") }
        }
    }
}

rootProject.name = "Cutly"
include(":app")
