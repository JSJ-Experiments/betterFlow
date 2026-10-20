package com.jadenjsj.betterflow

import java.io.File
import java.io.RandomAccessFile

/** Caller serializes access. Exact replay is safe; gaps, overlaps, and changed bytes are rejected. */
internal object HistoryChunkFile {
    fun append(file: File, offset: Long, total: Long, bytes: ByteArray): Boolean {
        require(total > 0 && offset in 0..total && bytes.size.toLong() <= total - offset) { "Invalid metadata chunk" }
        require(bytes.isNotEmpty() && bytes.size <= HistoryStore.MAX_CHUNK_BYTES) { "Invalid metadata chunk size" }
        return RandomAccessFile(file, "rw").use { output ->
            val current = output.length()
            require(current <= total) { "Invalid metadata length" }
            if (offset < current) {
                require(bytes.size.toLong() <= current - offset) { "Unexpected metadata offset" }
                output.seek(offset)
                require(ByteArray(bytes.size).also { output.readFully(it) }.contentEquals(bytes)) { "Conflicting metadata chunk" }
            } else {
                require(offset == current) { "Unexpected metadata offset" }
                output.seek(offset)
                output.write(bytes)
                output.fd.sync()
            }
            output.length() == total
        }
    }
}
