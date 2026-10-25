import org.gradle.api.initialization.resolve.RepositoriesMode
import org.gradle.api.JavaVersion

val runningJdkMajor = JavaVersion.current().majorVersion
val supportedJdkMajors = setOf("17", "27")
check(runningJdkMajor in supportedJdkMajors) {
    "AcousticGuard requires JDK 17 or JDK 27. Found Java $runningJdkMajor. " +
        "Set JAVA_HOME to a supported JDK before running Gradle."
}

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

rootProject.name = "AcousticGuard"
include(":app")
