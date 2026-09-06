package io.github.sebastianyousef.keel.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if the merged manifest ever asks for network access.
 *
 * These apps hold what you did in a gym, what you weigh and where you walked. Without
 * INTERNET none of it can leave the device, and the guarantee is enforced by the kernel
 * rather than by care — Android puts a process in the `inet` group only when the
 * permission is granted, so the syscall is refused rather than the code being trusted.
 *
 * The risk this exists for is not that anybody adds the permission deliberately. It is
 * that a dependency added two years from now declares it and manifest merging pulls it in
 * silently, turning a property the README states into one the app no longer has. This
 * makes that a build failure rather than a quiet regression.
 *
 * It reads the merged manifest through [com.android.build.api.artifact.SingleArtifact],
 * not by pattern-matching task names, so it cannot be left behind by a rename inside AGP
 * — and it declares its inputs and outputs, so it survives the configuration cache and
 * does not re-run when nothing has changed.
 */
@CacheableTask
abstract class CheckNoNetworkTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val forbidden: ListProperty<String>

    /**
     * A stamp, purely so the task has an output and can therefore be up-to-date. Checking
     * something is still work Gradle should be allowed to skip when its input has not
     * moved.
     */
    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest = mergedManifest.get().asFile
        val text = manifest.readText()
        val found = forbidden.get().filter { text.contains("\"$it\"") }
        if (found.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("This app must not have network access, but the merged manifest requests:")
                    found.forEach { appendLine("    $it") }
                    appendLine()
                    appendLine("Nothing in this repository asks for these, so something pulled one in")
                    appendLine("transitively. Find it with `./gradlew :app:dependencies`, then add a")
                    appendLine("""<uses-permission android:name="..." tools:node="remove" /> entry to""")
                    appendLine("the app's AndroidManifest.xml.")
                    appendLine()
                    appendLine("Do not delete this check. If network access is ever genuinely wanted,")
                    appendLine("that is a decision to argue for in docs/decisions.md and to remove from")
                    appendLine("the README's claims first — not a build error to silence.")
                    appendLine()
                    append("Manifest: ${manifest.absolutePath}")
                }
            )
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("no network: ${found.size} findings\n")
    }
}
