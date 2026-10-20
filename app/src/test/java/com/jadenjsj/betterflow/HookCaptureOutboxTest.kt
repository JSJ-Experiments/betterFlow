package com.jadenjsj.betterflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HookCaptureOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun emptyFastPathHandlesMissingRootAndFinalAcknowledgement() {
        val outbox = HookCaptureOutbox(File(temporary.newFolder(), "outbox"))
        assertFalse(outbox.hasEntries())
        val id = outbox.create(byteArrayOf(1, 2))
        assertTrue(outbox.hasEntries())
        assertTrue(outbox.contains(id))
        outbox.remove(id)
        assertFalse(outbox.hasEntries())
        assertFalse(outbox.contains(id))
    }

    @Test fun entryLookupDoesNotReadOrRecoverUnrelatedMetadata() {
        val root = temporary.newFolder()
        val outbox = HookCaptureOutbox(root)
        val id = outbox.create(byteArrayOf(1, 2))
        val unrelated = outbox.create(byteArrayOf(3, 4))
        val stateFile = File(File(root, unrelated), "state.json")
        stateFile.writeText("incomplete metadata")
        assertTrue(outbox.contains(id))
        assertEquals("incomplete metadata", stateFile.readText())
        assertEquals(0L, outbox.state(id).getLong("revision"))
    }

    @Test fun captureAndTranscriptSurviveBridgeOrKeyboardRestart() {
        val root = temporary.newFolder()
        val pcm = ByteArray(300_000) { (it % 127).toByte() }
        val original = HookCaptureOutbox(root)
        val id = original.create(pcm)
        original.update(id) { it.put("status", "transcribing").put("attempts", 1) }
        original.update(id) { it.put("status", "ready").put("transcript", "Saved transcript") }

        val restarted = HookCaptureOutbox(root)
        assertEquals(listOf(id), restarted.ids())
        assertArrayEquals(pcm, restarted.audio(id).readBytes())
        assertEquals("Saved transcript", restarted.state(id).getString("transcript"))
        assertEquals(2L, restarted.state(id).getLong("revision"))
        assertEquals("not_attempted", restarted.state(id).getString("insertion"))
    }

    @Test fun acknowledgesOnlyExplicitlyRemovedCaptureAndIgnoresTornTemporaryState() {
        val root = temporary.newFolder()
        val outbox = HookCaptureOutbox(root)
        val first = outbox.create(byteArrayOf(1, 2))
        val second = outbox.create(byteArrayOf(3, 4))
        File(File(root, second), "state.json.tmp").writeText("partial write")
        outbox.update(first) { it.put("insertion", "committed") }
        assertEquals(2, outbox.ids().size)
        outbox.remove(first)
        assertEquals(listOf(second), HookCaptureOutbox(root).ids())
        assertFalse(outbox.audio(first).exists())
        assertEquals("captured", outbox.state(second).getString("status"))
    }

    @Test fun recoversAudioWhenKeyboardDiesBeforeInitialMetadataCommit() {
        val root = temporary.newFolder()
        val outbox = HookCaptureOutbox(root)
        val id = outbox.create(byteArrayOf(1, 2, 3, 4))
        File(File(root, id), "state.json").delete()
        val restarted = HookCaptureOutbox(root)
        assertEquals(listOf(id), restarted.ids())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), restarted.audio(id).readBytes())
        assertEquals("failed", restarted.state(id).getString("status"))
    }

    @Test fun interruptedTranscriptionBecomesRetryableWithoutDiscardingAudio() {
        val root = temporary.newFolder()
        val outbox = HookCaptureOutbox(root)
        val id = outbox.create(byteArrayOf(1, 2))
        outbox.update(id) { it.put("status", "transcribing").put("owner_process", "previous-process") }
        assertEquals(listOf(id), outbox.ids())
        assertEquals("failed", outbox.state(id).getString("status"))
        assertArrayEquals(byteArrayOf(1, 2), outbox.audio(id).readBytes())
    }

    @Test fun rejectsNonCanonicalIdsBeforeReadingOutsideOutbox() {
        val outbox = HookCaptureOutbox(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { outbox.state("../outside") }
        assertThrows(IllegalArgumentException::class.java) { outbox.audio("bad-id") }
    }
}
