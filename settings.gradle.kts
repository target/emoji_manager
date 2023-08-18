rootProject.name = "emoji_manager"
include(
    "db-migration"
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

// This processing allows us to use the submodule name for submodule build files.
// Else they all need to be named build.gradle.kts, which is inconvenient when searching for the file
rootProject.children.forEach {
    renameBuildFiles(it)
}
fun renameBuildFiles(descriptor: ProjectDescriptor) {
    descriptor.buildFileName = "${descriptor.name}.gradle.kts"
    descriptor.children.forEach {
        renameBuildFiles(it)
    }
}
