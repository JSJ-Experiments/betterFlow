package com.jadenjsj.betterflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryChunkFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lostAcknowledgementCanReplayWithoutDuplicatingMetadata() {
        val file = temporary.newFile()
        assertFalse(HistoryChunkFile.append(file, 0, 6, byteArrayOf(1, 2)))
        assertFalse(HistoryChunkFile.append(file, 0, 6, byteArrayOf(1, 2)))
        assertFalse(HistoryChunkFile.append(file, 2, 6, byteArrayOf(3, 4)))
        assertTrue(HistoryChunkFile.append(file, 4, 6, byteArrayOf(5, 6)))
        assertTrue(HistoryChunkFile.append(file, 4, 6, byteArrayOf(5, 6)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), file.readBytes())
    }

    @Test fun conflictingReplayGapsAndOverlapsAreRejectedWithoutChangingSavedBytes() {
        val file = temporary.newFile()
        HistoryChunkFile.append(file, 0, 6, byteArrayOf(1, 2))
        assertThrows(IllegalArgumentException::class.java) { HistoryChunkFile.append(file, 0, 6, byteArrayOf(9, 9)) }
        assertThrows(IllegalArgumentException::class.java) { HistoryChunkFile.append(file, 3, 6, byteArrayOf(3, 4)) }
        assertThrows(IllegalArgumentException::class.java) { HistoryChunkFile.append(file, 1, 6, byteArrayOf(2, 3)) }
        assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
    }

    @Test fun largeMetadataUsesBoundedChunksAndResumesFromOnDiskBytes() {
        val file = temporary.newFile()
        val bytes = ByteArray(500_000) { (it % 113).toByte() }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + HistoryStore.MAX_CHUNK_BYTES, bytes.size)
            val complete = HistoryChunkFile.append(file, offset.toLong(), bytes.size.toLong(), bytes.copyOfRange(offset, end))
            if (end == bytes.size) assertTrue(complete) else assertFalse(complete)
            offset = end
        }
        assertArrayEquals(bytes, file.readBytes())
        assertThrows(IllegalArgumentException::class.java) {
            HistoryChunkFile.append(file, 0, bytes.size.toLong(), ByteArray(HistoryStore.MAX_CHUNK_BYTES + 1))
        }
    }
}
