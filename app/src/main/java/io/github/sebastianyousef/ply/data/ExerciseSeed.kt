package io.github.sebastianyousef.ply.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The vendored dataset's own shape, which is not the app's shape.
 *
 * Kept as a separate type on purpose. The two will diverge — the app already adds a rest
 * default, an archive flag and a bodyweight flag that the dataset knows nothing about —
 * and reading the file straight into the entity would mean every future column had to be
 * either optional in the JSON or defaulted in the parser.
 */
@Serializable
private data class SeedExercise(
    val name: String,
    val force: String? = null,
    val level: String = "intermediate",
    val mechanic: String? = null,
    val equipment: String? = null,
    val category: String = "strength",
    @SerialName("primaryMuscles") val primary: List<String> = emptyList(),
    @SerialName("secondaryMuscles") val secondary: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
)

/**
 * Copies the exercise library into the database, once.
 *
 * Runs on first launch rather than at build time because the alternative — shipping a
 * prebuilt `.db` in the assets — makes the schema a binary artifact that has to be rebuilt
 * and re-verified on every migration, and a Room migration against a database nobody can
 * read the history of is exactly the thing hand-written migrations exist to avoid.
 *
 * Insert-or-ignore keyed on the slug, so running it again is harmless and a later dataset
 * version adds what is new without touching what the user has edited. Deletion is never
 * part of seeding: a set points at an exercise, and an exercise dropped from the dataset
 * upstream must not take a year of history's labels with it.
 */
object ExerciseSeed {

    /** Raised when the vendored file changes, so a release can re-run the seed. */
    const val VERSION = 1

    private const val ASSET = "exercises.json"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedIfEmpty(context: Context, dao: PlyDao) {
        if (dao.exerciseCount() > 0) return
        dao.insertExercises(read(context))
    }

    internal fun read(context: Context): List<Exercise> {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString<List<SeedExercise>>(text).map { it.toEntity() }
    }

    private fun SeedExercise.toEntity() = Exercise(
        id = slug(name),
        name = name,
        force = force,
        level = level,
        mechanic = mechanic,
        equipment = equipment,
        category = category,
        primaryMuscles = Exercise.join(primary),
        secondaryMuscles = Exercise.join(secondary),
        instructions = instructions.joinToString("\n"),
        // The dataset has no field for this, so it is inferred once at seed time and then
        // owned by the user. "body only" is right for pull-ups, dips and push-ups, and
        // wrong for the handful of assisted machines it does not cover — which is why it
        // is editable rather than computed on every read.
        bodyweightLoaded = equipment == "body only",
        seedVersion = VERSION,
    )

    /**
     * The dataset's own id scheme: the name, punctuation dropped, spaces to underscores.
     *
     * Reimplemented rather than read from the file's `id` field so that a user-created
     * exercise and a shipped one are named by the same rule — and because the field was
     * stripped along with the image references when the asset was vendored.
     */
    internal fun slug(name: String): String =
        name.trim()
            .replace(Regex("[^A-Za-z0-9 /-]"), "")
            .replace(Regex("[ /-]+"), "_")
            .trim('_')
}
