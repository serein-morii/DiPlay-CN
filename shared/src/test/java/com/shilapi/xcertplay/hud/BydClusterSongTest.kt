package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterSongTest {
    @Test
    fun theGateReturnsToCarPlayInItsOwnTaskOrOnTopOfDiPlays() {
        val own = "com.shilapi.xcertplay.CarPlayHostActivity"
        val shared = "com.shilapi.xcertplay.SameTaskCarPlayHostActivity"
        val home = "com.shilapi.xcertplay.DiPlayActivity"

        assertTrue(BydClusterSong.isCarPlayTask(own, own))
        // "Keep CarPlay in the launcher card" (#463): CarPlay on top of DiPlay's own task.
        assertTrue(BydClusterSong.isCarPlayTask(home, shared))
        assertTrue(BydClusterSong.isCarPlayTask(shared, null))
        assertFalse(BydClusterSong.isCarPlayTask(home, home))
        assertFalse(BydClusterSong.isCarPlayTask(null, null))
    }

    private fun update(block: com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE, block)

    @Test
    fun followsTitleArtistAndPlaybackStatus() {
        val state = ClusterSongState()

        assertEquals(ClusterSong("Numb — Linkin Park", false, "Numb"),
            state.accept(update { group(0) { string(1, "Numb"); string(12, "Linkin Park") } }))
        assertEquals(ClusterSong("Numb — Linkin Park", true, "Numb"), state.accept(update { group(1) { u8(0, 1) } }))
        // Elapsed time alone changes nothing on the card.
        assertNull(state.accept(update { group(1) { u32(1, 120_706L) } }))
        assertEquals(ClusterSong("Numb — Linkin Park", false, "Numb"), state.accept(update { group(1) { u8(0, 2) } }))
        // A title-only incremental update retains the last artist.
        assertEquals(ClusterSong("Podcast — Linkin Park", false, "Podcast"), state.accept(update { group(0) { string(1, "Podcast") } }))
        assertEquals(ClusterSong("Podcast — Host", false, "Podcast"), state.accept(update { group(0) { string(12, "Host") } }))
    }

    @Test
    fun nothingWithoutATitleOrForOtherMessages() {
        val state = ClusterSongState()
        assertNull(state.accept(update { group(1) { u8(0, 1) } }))
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { group(0) { string(1, "Numb") } }))
        assertNull(state.current())

        state.accept(update { group(0) { string(1, "Numb") } })
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun clearedTitlesForgetThePreviousSongUntilANewTitleArrives() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Previous song"); string(12, "Artist") } })
        state.accept(update { group(0) { string(1, "") } })
        assertNull(state.current())
        state.accept(update { group(1) { u8(0, 1) } })
        assertNull(state.current())
        assertEquals(ClusterSong("Next song", true),
            state.accept(update { group(0) { string(1, "Next song") } }))
        state.accept(update { group(0) { string(1, "  "); string(12, "Stale artist") } })
        assertNull(state.current())
        assertEquals(ClusterSong("After clear", true),
            state.accept(update { group(0) { string(1, "After clear") } }))
    }

    @Test
    fun titleOnlyUpdatesRetainArtistAndPlaybackWhenOtherFieldsAreOmitted() {
        val state = ClusterSongState()
        state.accept(update {
            group(0) { string(1, "Track"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        assertEquals(ClusterSong("Lyric line — Artist", true, "Lyric line"),
            state.accept(update { group(0) { string(1, "Lyric line") } }))
        assertNull(state.accept(update { group(0) { u32(4, 180_000L) } }))
        assertEquals(ClusterSong("Lyric line — Artist", true, "Lyric line"), state.current())
    }

    @Test
    fun explicitEmptyArtistClearsItWithoutChangingTitleOrPlayback() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "Track"); string(12, "Artist") } })
        assertEquals(ClusterSong("Track", false),
            state.accept(update { group(0) { string(12, "") } }))
        assertEquals(ClusterSong("Next line", false),
            state.accept(update { group(0) { string(1, "Next line") } }))
    }

    @Test
    fun completeTrackUpdateReplacesBothTitleAndArtist() {
        val state = ClusterSongState()
        state.accept(update { group(0) { string(1, "First track"); string(12, "First artist") } })
        assertEquals(ClusterSong("Second track — Second artist", false, "Second track"),
            state.accept(update { group(0) { string(1, "Second track"); string(12, "Second artist") } }))
        assertEquals(ClusterSong("Third track", false),
            state.accept(update { group(0) { string(1, "Third track"); string(12, "") } }))
    }

    @Test
    fun textFitsTheDashboard() {
        assertNull(ClusterSongState.text("  ", "Artist"))
        assertEquals("Title", ClusterSongState.text(" Title ", ""))

        val long = ClusterSongState.text("Пісня".repeat(40), "Виконавець")!!
        assertTrue(long.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertEquals(127, long.length)

        // An emoji is never cut in half.
        val emoji = ClusterSongState.text("a" + "🎵".repeat(100), null)!!
        assertTrue(emoji.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(!Character.isHighSurrogate(emoji.last()))
    }
}
