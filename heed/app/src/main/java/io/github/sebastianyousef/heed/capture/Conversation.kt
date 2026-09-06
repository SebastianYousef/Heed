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
     * The **thread**: which conversation this arrived in.
     *
     * A shortcut id is assigned by the app itself to a conversation and survives renames,
     * so it is preferred wherever one exists. Below that the conversation title, then —
     * only for something actually flagged as a message — the notification title.
     *
     * The sender used to sit second in this chain, and that was the bug. For a group
     * chat in an app that sets no shortcut id, the "conversation" resolved to whoever
     * happened to speak last, so the flat's bin-day group had a different identity every
     * time a different person posted in it and the model could never learn the group at
     * all. The person is a real signal, but it is a *different* signal, and it now has
     * its own field — see [sender].
     */
    fun identify(notification: Notification): String? {
        val extras = notification.extras ?: return null

        notification.shortcutId?.takeIf { it.isNotBlank() }?.let { return key("s", it) }

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
     * The **person**: who wrote the message that just arrived.
     *
     * Held separately from [identify] because "which group" and "who in it" are two
     * different questions and the answer to one does not predict the other. Your partner
     * matters wherever they write; the bin-day group does not matter whoever writes in
     * it. Collapsing them into one identifier — which is what Heed used to do — means
     * the model can express one of those beliefs and never both.
     *
     * Null for anything that is not a message, and null for group chats in apps that do
     * not use MessagingStyle. Null is the honest answer there, and a notification with no
     * person scores on its content rather than being penalised for the gap.
     */
    fun sender(notification: Notification): String? {
        val extras = notification.extras ?: return null
        return senderOfNewestMessage(extras)?.let { key("m", it) }
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
