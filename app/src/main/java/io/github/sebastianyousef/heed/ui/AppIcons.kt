package io.github.sebastianyousef.heed.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.sebastianyousef.heed.focus.KnownScrollers
import java.util.concurrent.ConcurrentHashMap

/**
 * App icons and human names, cached.
 *
 * A list of package names is a list a developer can read. `com.zhiliaoapp.musically` is
 * TikTok, `com.google.android.apps.nbu.paisa.user` is Google Pay, and nobody setting a
 * time limit should have to know that. The icon does more work than the name — it is how
 * people actually recognise an app, and it turns a wall of identical rows into something
 * scannable at a glance.
 *
 * Cached in a map because these are asked for once per row per recomposition and each
 * lookup crosses into the package manager, which is far too slow to do while scrolling.
 * Heed has no network permission, so nothing here is fetched — every icon comes from the
 * APK already on the device.
 */
object AppIcons {

    /**
     * Wrapped rather than stored as a nullable bitmap because ConcurrentHashMap rejects
     * null values outright — an app whose icon cannot be loaded would otherwise take the
     * process down on the first frame that tried to draw it, which is exactly what it did.
     */
    private class Cached(val bitmap: ImageBitmap?)

    private val icons = ConcurrentHashMap<String, Cached>()
    private val labels = ConcurrentHashMap<String, String>()

    fun label(context: Context, pkg: String, fallback: String = pkg): String =
        labels.getOrPut(pkg) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }
                ?: KnownScrollers.packages[pkg]
                ?: fallback.takeIf { it.isNotBlank() && it != pkg }
                ?: prettify(pkg)
        }

    /**
     * A readable name for an app the system will not name for us.
     *
     * Uninstalled apps still have history worth showing, and an app can be invisible to
     * us for reasons that have nothing to do with Heed — a private-space install, a
     * profile boundary. Showing "com.zhiliaoapp.musically" in a list of where the evening
     * went is not an answer to anything, so the package name gets cleaned up rather than
     * printed raw.
     *
     * Takes the last segment that carries meaning, skipping the platform and vendor
     * noise that trails most package names.
     */
    fun prettify(pkg: String): String {
        val noise = setOf("android", "app", "apps", "mobile", "client", "com", "org", "io", "net", "free")
        val segment = pkg.split('.')
            .filter { it.isNotBlank() }
            .lastOrNull { it.lowercase() !in noise }
            ?: return pkg
        // Split runs like "deskclock" only where the app itself did, on camel case.
        return segment
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Null when the app is gone or invisible to us. Uninstalled apps still have sessions
     * in the database, and that history is worth keeping — so a missing icon has to be a
     * normal case rather than an error.
     */
    fun icon(context: Context, pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        Cached(
            runCatching {
                context.packageManager.getApplicationIcon(pkg)
                    .toBitmap(width = ICON_PX, height = ICON_PX)
                    .asImageBitmap()
            }.getOrNull()
        )
    }.bitmap

    private const val ICON_PX = 128
}

/** An app's icon, falling back to its initial when the icon cannot be loaded. */
@Composable
fun AppIcon(packageName: String, label: String, size: Int = 40) {
    val context = LocalContext.current
    // Decoded off the main thread. An app icon is an adaptive drawable that has to be
    // rasterised, and doing thirty of them inside composition is a visible stutter the
    // first time the list is drawn. Cached afterwards, so this only costs once.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) { AppIcons.icon(context, packageName) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size.dp),
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(size.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The name a person would use, resolved live so it survives a rename or a relabel. */
@Composable
fun rememberAppLabel(packageName: String, fallback: String): String {
    val context = LocalContext.current
    return remember(packageName, fallback) {
        AppIcons.label(context, packageName, fallback)
    }
}

/** Whether the package is still installed — used to grey out history for removed apps. */
fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
    context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
    true
}.getOrDefault(false)
