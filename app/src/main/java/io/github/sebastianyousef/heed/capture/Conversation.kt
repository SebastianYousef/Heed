package io.github.sebastianyousef.heed.capture

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import io.github.sebastianyousef.heed.export.Redaction

/**
 * Who a notification is from, as a stable identifier and nothing more.
 *
 * "Is this from a person who matters" is a much better predictor than "is this from
 * WhatsApp". WhatsApp is both your partner and the flat's bin-day group, and the app
 * name cannot tell them apart — which is why a filter that only knows the package ends
 * up either interrupting for everything or burying the one message you needed.
 *
 * What is stored is a hash, never the name. The identifier is stable, so the model can
 * learn "this thread is always worth it" across months; it is one-way, so the database
 * gains no new readable record of who you talk to; and it survives the retention scrub
 * that removes the notification's text, so what has been learned outlives what can be
 * read. That is the same bargain the rest of Heed makes, applied to the most sensitive
 * field it handles.
 */
object Conversation {

    /**
     * The most stable identity available, in descending order of trustworthiness.
     *
     * A shortcut id is assigned by the app itself to a conversation and survives renames,
     * so it is preferred wherever one exists. Below that, the sender of the newest
     * message in a MessagingStyle notification, then the conversation title, then the
     * notification title — which for a chat app is nearly always the person or group.
     */
    fun identify(notification: Notification): String? {
        val extras = notification.extras ?: return null

        notification.shortcutId?.takeIf { it.isNotBlank() }?.let { return key("s", it) }

        senderOfNewestMessage(extras)?.let { return key("p", it) }

        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.takeIf { it.isNotBlank() }?.let { return key("c", it) }

        // Only for things that are actually conversations. A title is the sender in a
        // chat app and the subject line everywhere else, and hashing subject lines would
        // produce a different identity for every message.
        if (notification.category == Notification.CATEGORY_MESSAGE) {
            extras.getCharSequence(Notification.EXTRA_TITLE)
                ?.toString()?.takeIf { it.isNotBlank() }?.let { return key("t", it) }
        }
        return null
    }

    /**
     * MessagingStyle keeps every message in the thread. The newest one's sender is the
     * person who just messaged you, which is the thing worth learning about — the others
     * are context.
     */
    private fun senderOfNewestMessage(extras: Bundle): String? {
        val messages = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull() ?: return null

        val newest = messages.lastOrNull() as? Parcelable ?: return null
        val bundle = newest as? Bundle ?: return null
        return bundle.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() }
    }

    /**
     * Namespaced so identities from different sources cannot collide — a shortcut id that
     * happens to equal someone's display name should not merge the two.
     */
    private fun key(kind: String, raw: String): String =
        kind + ":" + Redaction.pseudonym(raw.trim().lowercase())
}
