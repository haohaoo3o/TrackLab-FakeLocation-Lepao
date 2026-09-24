// TrackLab 单模块工程。契约见 docs/CONTRACTS.md §1（工程约束）、§2（工具链与依赖锁定）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TrackLab"
include(":app")
