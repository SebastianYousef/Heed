plugins {
    id("keel.android.library")
}

android {
    namespace = "io.github.sebastianyousef.keel"
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.material3)
    api(libs.androidx.material.icons.extended)
    api(libs.androidx.lifecycle.runtime.compose)

    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.ui.tooling.preview)

    testImplementation(libs.junit)
}
