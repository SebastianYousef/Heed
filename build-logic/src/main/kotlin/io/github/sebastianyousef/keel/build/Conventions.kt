package io.github.sebastianyousef.keel.build

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.assign
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.FileInputStream
import java.util.Properties

private val Project.libs
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun Project.version(alias: String) = libs.findVersion(alias).get().requiredVersion.toInt()

/**
 * The Android configuration that has no reason to differ between one app and the next.
 *
 * Takes [CommonExtension] rather than each app's own extension type so there is exactly
 * one copy of it — the library and the application land here from different directions and
 * leave configured the same way.
 */
private fun Project.commonAndroid(extension: CommonExtension) = with(extension) {
    compileSdk = version("compileSdk")
    defaultConfig.minSdk = version("minSdk")

    compileOptions.sourceCompatibility = JavaVersion.VERSION_21
    compileOptions.targetCompatibility = JavaVersion.VERSION_21

    buildFeatures.compose = true

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/*.version",
        "DebugProbesKt.bin",
    )

    // Configured on the compile tasks rather than through a `kotlin { }` block, so that
    // it holds whether Kotlin is compiled by the Kotlin plugin or by AGP itself — the
    // extension exists in one of those worlds and not the other, and which world this is
    // depends on a flag in gradle.properties that KSP currently forces.
    tasks.withType(KotlinJvmCompile::class.java).configureEach {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            // Warnings are the early form of the bugs an audit finds later, so they are
            // not allowed to accumulate into background noise nobody reads.
            allWarningsAsErrors = true
        }
    }
}

/**
 * An app in the family: no network, release-signed from a key outside the repository,
 * minified, and holding to the target SDK that both Play and Accrescent require.
 */
class ApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        /**
         * Release signing, from a keystore deliberately not in the repository.
         *
         * Without it a release build is unsigned and cannot be installed, which is how an
         * app ends up shipping *debug* builds to a real phone — carrying the DEBUGGABLE
         * flag, which lets anyone with adb read the database through `run-as`. For an app
         * whose whole premise is that the data stays on the device that is the wrong
         * default to leave in place, and Accrescent rejects both a debug certificate and
         * a debuggable manifest outright.
         *
         * If keystore.properties is absent the release build simply goes unsigned rather
         * than failing, so a fresh clone still builds and still runs the tests.
         */
        val keystore = Properties().apply {
            val file = rootProject.file("keystore.properties")
            if (file.exists()) FileInputStream(file).use(::load)
        }

        extensions.configure<ApplicationExtension> {
            commonAndroid(this)
            defaultConfig.targetSdk = version("targetSdk")
            buildFeatures.buildConfig = true

            // Configured through the returned objects rather than through configuration
            // lambdas, because AGP 9 out-projects these containers and a lambda taking the
            // element type is not callable on one.
            val signing = signingConfigs.create("release")
            keystore.getProperty("storeFile")?.let {
                signing.storeFile = rootProject.file(it)
                signing.storePassword = keystore.getProperty("storePassword")
                signing.keyAlias = keystore.getProperty("keyAlias")
                signing.keyPassword = keystore.getProperty("keyPassword")
            }

            buildTypes.getByName("release").apply {
                if (keystore.isNotEmpty()) signingConfig = signing
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro",
                )
            }
            // So a debug build can sit beside the release one that is actually in use,
            // rather than replacing it and taking its database with it.
            buildTypes.getByName("debug").applicationIdSuffix = ".debug"
        }

        wireNoNetworkCheck()

        dependencies {
            add("implementation", libs.findLibrary("androidx.core.ktx").get())
        }
    }
}

/** A library in the family. No signing, and no manifest of its own worth guarding. */
class LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<LibraryExtension> {
            commonAndroid(this)
            // A library that consumed the app's ProGuard rules would be a way for one
            // app's shrinking to differ from another's; the rules ship with the library
            // instead, so every consumer gets the same ones.
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
    }
}

/**
 * Runs [CheckNoNetworkTask] over every variant's merged manifest, and makes the APK depend
 * on it — so there is no way to produce an installable artifact that skipped it.
 */
private fun Project.wireNoNetworkCheck() {
    val forbiddenPermissions = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckNoNetworkTask>("check${name}HasNoNetwork") {
            group = "verification"
            description = "Fails if the ${variant.name} merged manifest asks for network access."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            forbidden.set(forbiddenPermissions)
            stamp.set(layout.buildDirectory.file("reports/no-network/${variant.name}.txt"))
        }
        // Attached to assemble rather than only to check, because the thing that must
        // never happen is an APK existing — not a test being skipped.
        //
        // Matched lazily rather than looked up by name: under AGP 9 this variant callback
        // runs before the assemble task it names has been registered, so a direct
        // `tasks.named` fails the whole build at configuration time.
        val wanted = setOf("assemble$name", "check")
        tasks.matching { it.name in wanted }.configureEach { dependsOn(check) }
    }
}
