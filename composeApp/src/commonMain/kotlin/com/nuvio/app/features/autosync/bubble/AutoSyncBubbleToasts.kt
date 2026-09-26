package com.nuvio.app.features.autosync.bubble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What an AutoSync message means for the bubble: still working, or how it ended. */
internal enum class AutoSyncBubbleKind { Working, Success, Failure }

/**
 * One AutoSync message as the bubble shows it. Messages of one run share [session], so the
 * bubble that shows "Analyzing…" is the one that turns into the check mark or the red card.
 */
internal data class AutoSyncBubbleMessage(
    val id: Long,
    val session: Long,
    val kind: AutoSyncBubbleKind,
    val headline: String,
    val detail: String?,
)

/**
 * The optional glass bubble that replaces AutoSync's plain toasts. On by default; persistence
 * is installed by the platform, and without it (or without a player on screen to draw it)
 * [post] returns false so the caller shows its plain toast instead.
 */
internal object AutoSyncBubbleToasts {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _current = MutableStateFlow<AutoSyncBubbleMessage?>(null)
    val current: StateFlow<AutoSyncBubbleMessage?> = _current.asStateFlow()

    private val hosts = MutableStateFlow(0)
    private var save: ((Boolean) -> Unit)? = null
    private var nextId = 0L

    val isAvailable: Boolean
        get() = save != null

    fun installPersistence(load: () -> Boolean, save: (Boolean) -> Unit) {
        _enabled.value = load()
        this.save = save
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        save?.invoke(enabled)
        if (!enabled) _current.value = null
    }

    /** Shows [text] (an "Auto Sync • headline • detail" toast string) in the bubble, if it can. */
    fun post(kind: AutoSyncBubbleKind, text: String): Boolean {
        if (!_enabled.value || hosts.value == 0) return false
        val (headline, detail) = split(kind, text)
        _current.update { previous ->
            val id = ++nextId
            // A run continues while the bubble is still working; anything else starts a new bubble.
            val session = if (previous != null && previous.kind == AutoSyncBubbleKind.Working) previous.session else id
            AutoSyncBubbleMessage(id, session, kind, headline, detail)
        }
        return true
    }

    /** Called by the bubble once it has finished animating [id] away. */
    fun finished(id: Long) {
        _current.update { if (it?.id == id) null else it }
    }

    fun attachHost() {
        hosts.update { it + 1 }
    }

    fun detachHost() {
        hosts.update { it - 1 }
        if (hosts.value <= 0) _current.value = null
    }

    /**
     * AutoSync's toast strings read "Auto Sync • headline • detail" in every language. The bubble
     * already says it is AutoSync, so it drops that part. While working it shows only what is
     * happening now ("syncing to the audio instead…"); a result keeps the rest as its explanation.
     */
    internal fun split(kind: AutoSyncBubbleKind, text: String): Pair<String, String?> {
        val parts = text.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (parts.size > 1) parts.drop(1) else parts.ifEmpty { listOf(text) }
        return when (kind) {
            AutoSyncBubbleKind.Working -> body.last().capitalized() to null
            else -> body.first().capitalized() to
                body.drop(1).joinToString(" · ").takeIf { it.isNotEmpty() }?.capitalized()
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
}
