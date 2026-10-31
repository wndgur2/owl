package dev.owl.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Raw segment files in app-private storage (`filesDir/segments/<device>/`).
 *
 * While receiving, bytes go to `<id>.part`; its length is how far we got, so a FETCH after a
 * reconnect resumes from there. When complete it is fsynced and renamed to `<id>.owls`.
 * Only after that rename does the sync engine send ACK (PRD A3, protocol §5).
 */
class SegmentFiles(private val root: File) {
    private fun dir(address: String): File =
        File(root, address.replace(':', '_')).apply { mkdirs() }

    fun partFile(address: String, id: Long) = File(dir(address), "$id.part")
    fun finalFile(address: String, id: Long) = File(dir(address), "$id.owls")

    /** Bytes already stored for [id] (resume offset). */
    fun partialLength(address: String, id: Long): Long = partFile(address, id).let { if (it.exists()) it.length() else 0L }

    /** Appends at exactly [offset]; anything beyond it is cut off first (e.g. after a crash mid-write). */
    fun append(address: String, id: Long, offset: Long, bytes: ByteArray) {
        RandomAccessFile(partFile(address, id), "rw").use { f ->
            if (f.length() != offset) f.setLength(offset)
            f.seek(offset)
            f.write(bytes)
        }
    }

    fun readHeaderBytes(address: String, id: Long, count: Int): ByteArray =
        RandomAccessFile(partFile(address, id), "r").use { f -> ByteArray(count).also { f.readFully(it) } }

    fun crcOfPart(address: String, id: Long): Long = crcOf(partFile(address, id))

    /**
     * Makes the part durable and moves it to its final name. fsync on the file, then on the
     * directory, so the rename itself survives a power cut.
     */
    fun commit(address: String, id: Long): File {
        val part = partFile(address, id)
        FileOutputStream(part, true).use { it.fd.sync() }
        val dest = finalFile(address, id)
        if (!part.renameTo(dest)) throw java.io.IOException("rename failed for $part")
        try {
            FileChannel.open(dest.parentFile!!.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Some filesystems refuse to open a directory; the file data itself is already synced.
        }
        return dest
    }

    fun discard(address: String, id: Long) {
        partFile(address, id).delete()
        finalFile(address, id).delete()
    }

    companion object {
        fun crcOf(file: File): Long {
            val crc = CRC32()
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    crc.update(buf, 0, n)
                }
            }
            return crc.value
        }
    }
}
