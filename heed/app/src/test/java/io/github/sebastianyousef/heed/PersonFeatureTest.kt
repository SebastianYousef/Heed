package io.github.sebastianyousef.heed

import io.github.sebastianyousef.heed.data.NotificationRecord
import io.github.sebastianyousef.heed.score.FeatureExtractor
import io.github.sebastianyousef.heed.score.OnlineClassifier
import io.github.sebastianyousef.heed.score.SenderHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group and the person in it are two different questions.
 *
 * Heed used to answer only one of them, and which one depended on the app: the identifier
 * was "shortcut id, else whoever spoke last, else the conversation title". For a group
 * chat in an app that sets no shortcut id, that resolved to a different identity every
 * time a different person posted, so the flat's bin-day group could never be learned at
 * all — and the one person in it whose messages you always want could never be separated
 * from the rest.
 *
 * Both now travel on every record, in two independent hashed blocks, and this is what
 * that has to buy: a model that can hold "this group is noise" and "this person is not"
 * at the same time.
 */
class PersonFeatureTest {

    private fun record(
        conversation: String?,
        person: String?,
        text: String = "are you around",
    ) = NotificationRecord(
        sbnKey = "k$conversation$person",
        packageName = "com.whatsapp",
        appLabel = "WhatsApp",
        title = "Someone",
        text = text,
        category = "msg",
        postedAt = System.currentTimeMillis(),
        conversationId = conversation,
        senderId = person,
    )

    @Test
    fun `the person lands in its own block, not the thread's`() {
        val group = FeatureExtractor.extract(record("c:flat", null))
        val groupAndPerson = FeatureExtractor.extract(record("c:flat", "m:alice"))

        // Same thread, so the thread's bucket is untouched and one bucket is added.
        assertEquals(group.size + 1, groupAndPerson.size)

        val added = groupAndPerson.indices.toSet() - group.indices.toSet()
        assertEquals(1, added.size)
        val personOffset = FeatureExtractor.TEXT_DIM + FeatureExtractor.APP_DIM +
            FeatureExtractor.STRUCT_DIM + FeatureExtractor.PERSON_DIM
        assertTrue(
            "person bucket should sit in the appended block, got ${added.first()}",
            added.first() >= personOffset && added.first() < FeatureExtractor.DIM,
        )
    }

    @Test
    fun `two people in the same group are different to the model`() {
        val alice = FeatureExtractor.extract(record("c:flat", "m:alice"))
        val bob = FeatureExtractor.extract(record("c:flat", "m:bob"))
        assertNotEquals(alice.indices.toSet(), bob.indices.toSet())
    }

    @Test
    fun `the same person in two groups shares a weight`() {
        val here = FeatureExtractor.extract(record("c:flat", "m:alice")).indices.toSet()
        val there = FeatureExtractor.extract(record("c:work", "m:alice")).indices.toSet()
        // The thread bucket differs; the person bucket is common to both.
        assertTrue("expected a shared bucket", (here intersect there).isNotEmpty())
    }

    /**
     * The point of the whole exercise: opposite lessons about a group and a person in it,
     * held at once. Without the second block the two collide in one weight and the later
     * lesson simply overwrites the earlier one.
     */
    @Test
    fun `a muted group does not mute the person you care about`() {
        val classifier = OnlineClassifier()
        val noiseFromGroup = record("c:flat", "m:bob", "anyone taking the bins out")
        val theOneThatMatters = record("c:flat", "m:alice", "anyone taking the bins out")

        repeat(40) {
            classifier.train(FeatureExtractor.extract(noiseFromGroup), label = 0f, weight = 1f)
            classifier.train(FeatureExtractor.extract(theOneThatMatters), label = 1f, weight = 1f)
        }

        val bob = classifier.predict(FeatureExtractor.extract(noiseFromGroup))
        val alice = classifier.predict(FeatureExtractor.extract(theOneThatMatters))
        assertTrue(
            "same group and same words, so only the person can separate these: " +
                "bob=$bob alice=$alice",
            alice - bob > 0.3f,
        )
    }

    /**
     * A person never seen before scores on content, not on unfamiliarity.
     *
     * The same rule the thread block already follows, and for the same reason: penalising
     * an unknown sender filters exactly the first message from someone new, which is the
     * message that most needs to arrive.
     */
    @Test
    fun `an unknown person is neutral rather than negative`() {
        val known = FeatureExtractor.extract(
            record("c:flat", "m:alice"),
            person = SenderHistory(seen = 20, engagement = 0.9f),
        )
        val unknown = FeatureExtractor.extract(record("c:flat", "m:stranger"))
        val structOffset = FeatureExtractor.TEXT_DIM + FeatureExtractor.APP_DIM

        fun slot(f: io.github.sebastianyousef.heed.score.Features, name: String): Float {
            val index = structOffset + FeatureExtractor.STRUCT_NAMES.indexOf(name)
            val at = f.indices.indexOf(index)
            return if (at < 0) 0f else f.values[at]
        }
        assertEquals(1f, slot(known, "known_person"), 1e-6f)
        assertEquals(0f, slot(unknown, "known_person"), 1e-6f)
        assertEquals(0f, slot(unknown, "person_engagement"), 1e-6f)
    }

    /**
     * The appended block must not have moved anything that came before it.
     *
     * Every weight the model has learned to date is an index into this vector. Reordering
     * or inserting would silently reassign all of them, which looks exactly like the app
     * forgetting the user overnight.
     */
    @Test
    fun `existing blocks keep their offsets`() {
        assertEquals(4096, FeatureExtractor.TEXT_DIM)
        assertEquals(256, FeatureExtractor.APP_DIM)
        assertEquals(32, FeatureExtractor.STRUCT_DIM)
        assertEquals(512, FeatureExtractor.PERSON_DIM)
        assertEquals(
            FeatureExtractor.TEXT_DIM + FeatureExtractor.APP_DIM +
                FeatureExtractor.STRUCT_DIM + FeatureExtractor.PERSON_DIM +
                FeatureExtractor.SENDER_DIM,
            FeatureExtractor.DIM,
        )
        // The new structured slots sit inside the existing block, in indices that were
        // always zero — so they cost no offset either.
        assertTrue(FeatureExtractor.STRUCT_NAMES.size <= FeatureExtractor.STRUCT_DIM)
        assertEquals("known_person", FeatureExtractor.STRUCT_NAMES[28])
    }
}
