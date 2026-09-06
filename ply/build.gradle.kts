plugins {
    id("keel.android.application")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.sebastianyousef.ply"

    defaultConfig {
        applicationId = "io.github.sebastianyousef.ply"

        // 0.x, and staying there. The previous app reached 1.0 in four days, which said
        // nothing true about it — a version number is a claim about how settled something
        // is, and spending it early leaves nothing to say when it becomes settled.
        versionCode = 1
        versionName = "0.1.0"
    }
}

/**
 * Room's schema JSON is committed, so that a migration can be reviewed against the shape
 * it actually produced rather than against the shape its author believed it produced.
 */
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(project(":keel"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
