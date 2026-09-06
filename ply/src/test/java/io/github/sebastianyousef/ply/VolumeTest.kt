package io.github.sebastianyousef.ply

import io.github.sebastianyousef.ply.train.Volume
import io.github.sebastianyousef.ply.train.VolumeSet
import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeTest {

    private fun bench(warmUp: Boolean = false) = VolumeSet(
        weightGrams = 100_000,
        reps = 5,
        warmUp = warmUp,
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "shoulders"),
    )

    @Test
    fun `a secondary muscle gets half a set`() {
        val volume = Volume.aggregate(listOf(bench(), bench())).associateBy { it.muscle }
        assertEquals(2.0, volume.getValue("chest").hardSets, 0.0)
        assertEquals(1.0, volume.getValue("triceps").hardSets, 0.0)
        assertEquals(1.0, volume.getValue("shoulders").hardSets, 0.0)
    }

    @Test
    fun `warm-ups are not stimulus and are not counted`() {
        val volume = Volume.aggregate(listOf(bench(warmUp = true), bench())).associateBy { it.muscle }
        assertEquals(1.0, volume.getValue("chest").hardSets, 0.0)
        assertEquals(500_000L, volume.getValue("chest").tonnageGrams)
    }

    @Test
    fun `a muscle listed as both primary and secondary is credited once`() {
        // The dataset does contain this. Without the subtraction it would score 1.5.
        val set = VolumeSet(
            weightGrams = 50_000,
            reps = 10,
            warmUp = false,
            primaryMuscles = listOf("lats"),
            secondaryMuscles = listOf("lats", "biceps"),
        )
        val volume = Volume.aggregate(listOf(set)).associateBy { it.muscle }
        assertEquals(1.0, volume.getValue("lats").hardSets, 0.0)
        assertEquals(0.5, volume.getValue("biceps").hardSets, 0.0)
    }

    @Test
    fun `tonnage follows the same warm-up and secondary rules as hard sets`() {
        val volume = Volume.aggregate(listOf(bench())).associateBy { it.muscle }
        assertEquals(500_000L, volume.getValue("chest").tonnageGrams)
        assertEquals(250_000L, volume.getValue("triceps").tonnageGrams)
    }

    @Test
    fun `the two ways of counting disagree, which is why both are shown`() {
        val squat = VolumeSet(200_000, 5, false, listOf("quadriceps"), emptyList())
        val curl = VolumeSet(20_000, 5, false, listOf("biceps"), emptyList())
        val volume = Volume.aggregate(listOf(squat, curl)).associateBy { it.muscle }

        // Identical by hard sets, ten times apart by tonnage. Neither is wrong; they are
        // answering different questions, and an app that prints one number called "volume"
        // has picked one of them without saying so.
        assertEquals(volume.getValue("quadriceps").hardSets, volume.getValue("biceps").hardSets, 0.0)
        assertEquals(10L, volume.getValue("quadriceps").tonnageGrams / volume.getValue("biceps").tonnageGrams)
    }

    @Test
    fun `an empty week aggregates to nothing rather than to zeroes`() {
        assertEquals(emptyList<Any>(), Volume.aggregate(emptyList()))
        assertEquals(0, Volume.workingSets(emptyList()))
        assertEquals(0L, Volume.tonnageGrams(emptyList()))
    }
}
