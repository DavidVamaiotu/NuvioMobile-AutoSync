package com.nuvio.app.features.autosync.bubble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoSyncBubbleToastsTest {
    @Test
    fun workingShowsWhatIsHappeningNow() {
        assertEquals(
            "Analyzing…" to null,
            AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"),
        )
        assertEquals(
            "Syncing to the audio instead…" to null,
            AutoSyncBubbleToasts.split(
                AutoSyncBubbleKind.Working,
                "Auto Sync • No match found • syncing to the audio instead…",
            ),
        )
    }

    @Test
    fun failureKeepsTheRestAsItsExplanation() {
        assertEquals(
            "No embedded subtitles to compare with" to "Subtitle timing not changed",
            AutoSyncBubbleToasts.split(
                AutoSyncBubbleKind.Failure,
                "Auto Sync • No embedded subtitles to compare with • subtitle timing not changed",
            ),
        )
        assertEquals(
            "Subtitles synced" to null,
            AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced"),
        )
    }

    @Test
    fun audioSyncMessagesWithoutPartsStayWhole() {
        val text = "Couldn't confirm subtitle sync, restored original timing"
        assertEquals(text to null, AutoSyncBubbleToasts.split(AutoSyncBubbleKind.Failure, text))
    }

    @Test
    fun onlyPostsWhenTurnedOnAndAPlayerIsShowing() {
        var saved: Boolean? = null
        AutoSyncBubbleToasts.installPersistence(load = { false }, save = { saved = it })
        AutoSyncBubbleToasts.attachHost()
        try {
            assertFalse(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))

            AutoSyncBubbleToasts.setEnabled(true)
            assertEquals(true, saved)
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            val working = AutoSyncBubbleToasts.current.value!!
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced"))
            val done = AutoSyncBubbleToasts.current.value!!
            assertEquals(working.session, done.session)

            // A new message after a result starts a new bubble.
            assertTrue(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
            assertTrue(AutoSyncBubbleToasts.current.value!!.session != done.session)

            AutoSyncBubbleToasts.finished(done.id) // stale: leaves the new bubble alone
            assertTrue(AutoSyncBubbleToasts.current.value != null)
        } finally {
            AutoSyncBubbleToasts.detachHost()
            AutoSyncBubbleToasts.setEnabled(false)
        }
        assertNull(AutoSyncBubbleToasts.current.value)
        assertFalse(AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…"))
    }
}
