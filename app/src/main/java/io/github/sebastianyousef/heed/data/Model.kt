package io.github.sebastianyousef.heed.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** What we ultimately did with a notification. */
enum class Decision {
    /** Passed through / re-raised as a real alert. */
    ALERTED,

    /** Held in the buffer, still deciding. */
    HELD,

    /** Filed silently. Lives in the inbox and the next digest, never interrupted the user. */
    SUPPRESSED,
}

/** How we managed to act on it — determines whether the user was interrupted. */
enum class CapturePath {
    /** NotificationAssistantService.onNotificationEnqueued — demoted before display. Clean. */
    ASSISTANT,

    /** Listener saw it, but the source app was already silenced, so nothing alerted. Clean. */
    QUIET_SOURCE,

    /** Listener cancelled it after posting. The user may have seen/heard a flash. */
    CANCEL_AFTER,
}

/** Explicit or implicit signal about whether the notification mattered. */
enum class Feedback {
    NONE,

    /** User tapped it. Strong positive. */
    CLICKED,

    /** User swiped it away without opening. Weak negative. */
    DISMISSED,

    /** User pressed "this mattered" in the inbox. Strongest positive. */
    MARKED_IMPORTANT,

    /** User pressed "noise" in the inbox. Strongest negative. */
    MARKED_NOISE,

    /**
     * Tapped, and what followed was a long scroll rather than anything purposeful.
     *
     * Treating a tap as approval is the obvious mistake in a system like this. Bait works
     * precisely by getting tapped. When the session that followed was doom-scrolling, the
     * notification did its job for the app and not for you, and the model should learn it
     * as a negative rather than a win.
     */
    CLICKED_THEN_SCROLLED,
}

/** Per-app override. LEARN means the classifier decides. */
enum class AppPolicy { LEARN, ALWAYS_ALERT, NEVER_ALERT }

@Entity(
    tableName = "notifications",
    indices = [Index("postedAt"), Index("packageName"), Index("decision"), Index("sbnKey")],
)
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** StatusBarNotification.key — stable while the notification is live. */
    val sbnKey: String,
    val packageName: String,
    val appLabel: String,

    val title: String? = null,
    val text: String? = null,
    val bigText: String? = null,
    val subText: String? = null,

    /** Notification.category, e.g. msg / call / alarm / promo / social. */
    val category: String? = null,
    val channelId: String? = null,

    /** NotificationChannel importance as ranked by the system (0..5). */
    val systemImportance: Int = 3,

    val postedAt: Long,

    /**
     * Hash of the visible text. Apps update a notification in place under the same key —
     * a chat thread gaining a message, a download ticking up — and Android re-fires
     * onNotificationPosted every time. Comparing this tells an actual change from a
     * repost of identical content.
     */
    val contentHash: Int = 0,

    /** How many times this same notification has been re-posted or updated. */
    val updateCount: Int = 1,

    val isOngoing: Boolean = false,
    val isGroupSummary: Boolean = false,
    val hasPerson: Boolean = false,

    /** 0..1, higher = more likely to matter to this user. */
    val score: Float = 0f,

    /** Human-readable trace of what drove the score. Shown in the detail screen. */
    val scoreReason: String = "",

    val decision: Decision = Decision.HELD,
    val capturePath: CapturePath = CapturePath.QUIET_SOURCE,

    val feedback: Feedback = Feedback.NONE,
    val feedbackAt: Long? = null,

    /** Set once this record has been rolled into a digest. */
    val digestId: Long? = null,

    /** True once the user has laid eyes on it in the inbox. */
    val seen: Boolean = false,

    /**
     * When the text of this notification was scrubbed, or null while it is still here.
     *
     * Scrubbing costs the model nothing. Training happens the moment you react to a
     * notification, and the resulting weights live in [ModelState] — a separate blob that
     * this never touches. What is lost is only the ability to read back what was said.
     */
    val redactedAt: Long? = null,

    /** Derived shape of the original text, kept so scrubbed rows still explain themselves. */
    val textShape: String? = null,
) {
    /** All the text we classify on, concatenated. */
    val body: String
        get() = listOfNotNull(title, text, bigText, subText).joinToString("\n")
}

/**
 * A (package, channel) pair that posts live-updating notifications: step counters,
 * download progress, navigation, timers, sync status. These are ambient displays rather
 * than events — they are never worth an alert, and left unchecked a step counter alone
 * can post thousands of updates a day.
 *
 * Detected by watching update rate rather than trusting flags, because plenty of apps
 * post frequently-updating notifications without setting FLAG_ONGOING_EVENT.
 */
@Entity(tableName = "live_channels", primaryKeys = ["packageName", "channelId"])
data class LiveChannelRecord(
    val packageName: String,
    val channelId: String,
    val appLabel: String,
    val detectedAt: Long,
    /** Updates observed in the window that triggered detection — shown in the UI. */
    val burstSize: Int,
)

@Entity(tableName = "app_policies")
data class AppPolicyRecord(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val policy: AppPolicy = AppPolicy.LEARN,

    /**
     * True once the user has confirmed this app's channels are set to silent in Android
     * settings. Only then can we hold its notifications without them having already
     * made noise. Drives the onboarding checklist.
     */
    val sourceSilenced: Boolean = false,

    val alertedCount: Int = 0,
    val suppressedCount: Int = 0,
    val lastSeenAt: Long = 0,
)

@Entity(tableName = "digests")
data class DigestRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val windowStart: Long,
    val windowEnd: Long,
    val notificationCount: Int,
    /** Rendered summary text. Templated, or LLM-written when a summarizer is installed. */
    val summary: String,
    val delivered: Boolean = false,
)

/** Single-row table holding the serialised online classifier. */
@Entity(tableName = "model_state")
data class ModelState(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val weights: ByteArray,
    val bias: Float,
    /** Number of training examples seen. Drives how much we trust the model vs. the rules. */
    val examplesSeen: Int,
    val updatedAt: Long,
) {
    override fun equals(other: Any?) =
        other is ModelState && id == other.id && updatedAt == other.updatedAt
    override fun hashCode() = id * 31 + updatedAt.hashCode()
}

class Converters {
    @TypeConverter fun detectionTo(v: io.github.sebastianyousef.heed.focus.DetectionMode) = v.name
    @TypeConverter fun detectionFrom(v: String) = io.github.sebastianyousef.heed.focus.DetectionMode.valueOf(v)
    @TypeConverter fun focusModeTo(v: io.github.sebastianyousef.heed.focus.FocusMode) = v.name
    @TypeConverter fun focusModeFrom(v: String) = io.github.sebastianyousef.heed.focus.FocusMode.valueOf(v)
    @TypeConverter fun decisionTo(v: Decision) = v.name
    @TypeConverter fun decisionFrom(v: String) = Decision.valueOf(v)
    @TypeConverter fun pathTo(v: CapturePath) = v.name
    @TypeConverter fun pathFrom(v: String) = CapturePath.valueOf(v)
    @TypeConverter fun feedbackTo(v: Feedback) = v.name
    @TypeConverter fun feedbackFrom(v: String) = Feedback.valueOf(v)
    @TypeConverter fun policyTo(v: AppPolicy) = v.name
    @TypeConverter fun policyFrom(v: String) = AppPolicy.valueOf(v)
}
