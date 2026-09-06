plugins {
    `kotlin-dsl`
}

/**
 * The build configuration every app in the family shares.
 *
 * Two apps had two copies of the same eighty lines of `android { }` block, the same
 * signing config and the same merged-manifest guard — which is the shape of thing that
 * looks harmless right up until one copy is edited and the other is not. A guard that
 * only holds in one of two apps is worse than no guard, because it still reads as a
 * policy. Here it is one implementation with two consumers.
 */
kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("keelApplication") {
            id = "keel.android.application"
            implementationClass = "io.github.sebastianyousef.keel.build.ApplicationConventionPlugin"
        }
        register("keelLibrary") {
            id = "keel.android.library"
            implementationClass = "io.github.sebastianyousef.keel.build.LibraryConventionPlugin"
        }
    }
}
