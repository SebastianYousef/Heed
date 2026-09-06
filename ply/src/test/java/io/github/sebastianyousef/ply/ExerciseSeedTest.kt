package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.data.Exercise
import io.github.sebastianyousef.ply.data.ExerciseSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the real vendored file through the real parser.
 *
 * Seeding happens once, on a phone, on first launch, and its failure mode is an app with an
 * empty exercise library — which looks exactly like an app that is simply broken. Every
 * other test here covers a rule; this one covers the 800 KB of data those rules operate on,
 * and it is the only thing standing between a malformed entry and a first launch that
 * silently produces nothing.
 */
class ExerciseSeedTest {

    private val exercises: List<Exercise> by lazy {
        ExerciseSeed.parse(File("src/main/assets/exercises.json").readText())
    }

    @Test
    fun `the whole vendored library parses`() {
        assertEquals(876, exercises.size)
    }

    @Test
    fun `every exercise has an id, a name and at least one primary muscle`() {
        val broken = exercises.filter {
            it.id.isBlank() || it.name.isBlank() || it.primary.isEmpty()
        }
        assertEquals(emptyList<Exercise>(), broken)
    }

    @Test
    fun `ids are unique, because a collision would silently drop an exercise`() {
        // Seeding is INSERT OR IGNORE keyed on the id, so two exercises sharing one would
        // not fail — the second would simply never appear, and nothing would say so.
        val duplicates = exercises.groupBy { it.id }.filter { it.value.size > 1 }
        assertEquals(emptyMap<String, List<Exercise>>(), duplicates)
    }

    @Test
    fun `muscles come from the closed vocabulary the picker filters on`() {
        // The muscle chips are a hard-coded list. Anything outside it is an exercise that
        // can never be found by filtering, which is a silent hole rather than an error.
        val known = setOf(
            "chest", "shoulders", "triceps", "lats", "middle back", "biceps", "traps",
            "forearms", "quadriceps", "hamstrings", "glutes", "calves", "abdominals",
            "lower back", "abductors", "adductors", "neck",
        )
        val unexpected = exercises.flatMap { it.primary + it.secondary }.toSet() - known
        assertEquals(emptySet<String>(), unexpected)
    }

    @Test
    fun `the instructions survived being vendored`() {
        // They are the reason the file is worth its size; a strip that took them out
        // along with the images would leave a library of bare names.
        val withSteps = exercises.count { it.steps.isNotEmpty() }
        assertTrue("only $withSteps of ${exercises.size} have instructions", withSteps > 850)
    }

    @Test
    fun `bodyweight exercises are recognised as such`() {
        val pullUp = exercises.single { it.name == "Pullups" }
        assertTrue(pullUp.bodyweightLoaded)

        val benchPress = exercises.first { it.name.startsWith("Barbell Bench Press") }
        assertTrue(!benchPress.bodyweightLoaded)
    }

    @Test
    fun `the slug is stable and readable`() {
        assertEquals("Pullups", ExerciseSeed.slug("Pullups"))
        assertEquals("3_4_Sit-Up", ExerciseSeed.slug("3/4 Sit-Up"))
        assertEquals("Ab_Roller", ExerciseSeed.slug("Ab Roller"))
    }
}
