plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.sebastianyousef.heed"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sebastianyousef.heed"
        minSdk = 26          // NotificationListenerService removal-reasons + channels
        targetSdk = 35
        versionCode = 18
        versionName = "0.9.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // org.json ships with Android but is stubbed out in the JVM test runtime; this gives
    // the redaction tests a real implementation to run against.
    testImplementation(libs.json)
}

/**
 * Fails the build if the merged manifest ever asks for network access.
 *
 * Heed holds notification content, app usage and scrolling behaviour. Without INTERNET
 * none of that can leave the device, and the guarantee is enforced by the kernel rather
 * than by care. The risk is not that anyone adds the permission deliberately — it is that
 * a dependency added two years from now declares it and manifest merging pulls it in
 * silently. This makes that a build failure instead of a quiet regression.
 */
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
)

tasks.matching { it.name.matches(Regex("process[A-Z].*MainManifest")) }.configureEach {
    doLast {
        val manifests = outputs.files.asFileTree.matching { include("**/AndroidManifest.xml") }
        manifests.forEach { manifest ->
            val text = manifest.readText()
            val found = forbiddenPermissions.filter { text.contains("\"$it\"") }
            if (found.isNotEmpty()) {
                throw GradleException(
                    "Heed must not have network access, but the merged manifest requests: " +
                        found.joinToString() + "\n" +
                        "Something pulled this in transitively. Find it with " +
                        "`./gradlew :app:dependencies`, then add a tools:node=\"remove\" " +
                        "entry in AndroidManifest.xml. Do not delete this check.\n" +
                        "Manifest: ${manifest.absolutePath}"
                )
            }
        }
    }
}
