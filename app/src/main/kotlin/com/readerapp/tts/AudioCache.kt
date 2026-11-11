package com.readerapp.tts

import java.io.File
import java.util.zip.CRC32

/**
 * Identity of one synthesized sentence. The text hash invalidates entries when a variant is re-processed; [model] is
 * the short [TtsModel.tag], so another model never reuses a clip, and [TtsConfig.SHAPING_VERSION] separates clips
 * made before the current trimming / pause rules.
 */
data class AudioKey(
    val chapterId: Long,
    val variant: String,
    val model: String,
    val voice: Int,
    val speed: Float,
    val paragraph: Int,
    val sentence: Int,
    val textHash: Long,
) {
    fun fileName(): String =
        "c${chapterId}_${variant}_m${model}_h${TtsConfig.SHAPING_VERSION}_v${voice}_s${Math.round(speed * 100)}_p${paragraph}_${sentence}_${textHash.toString(16)}.wav"

    companion object {
        fun hash(text: String): Long = CRC32().also { it.update(text.toByteArray()) }.value
    }
}

/**
 * Directory of WAV files with least-recently-used eviction under [maxBytes]. "Used" = file modification time, which
 * [get] and [put] refresh (the clock is injectable for tests).
 */
class AudioCache(private val dir: File, @Volatile var maxBytes: Long, private val clock: () -> Long = System::currentTimeMillis) {
    init {
        dir.mkdirs()
    }

    private val pinned = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Files referenced by a live playlist are never evicted while pinned (pins are counted). */
    fun pin(name: String) { pinned.merge(name, 1, Int::plus) }

    fun unpin(name: String) { pinned.computeIfPresent(name) { _, n -> if (n <= 1) null else n - 1 } }

    fun file(key: AudioKey): File = File(dir, key.fileName())

    /** A complete cached file for [key], marked as recently used; null if absent. */
    fun get(key: AudioKey): File? {
        val f = file(key)
        if (!f.isFile || f.length() <= WavWriter.HEADER_BYTES) return null
        f.setLastModified(clock())
        return f
    }

    /** Writes via a temp file so a half-written WAV never looks like a cache hit. */
    fun put(key: AudioKey, write: (File) -> Unit): File {
        val target = file(key)
        val tmp = File(dir, target.name + ".tmp")
        write(tmp)
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not move audio into the cache" }
        target.setLastModified(clock())
        return target
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes least recently used files until the cache fits [maxBytes]; files in [protectedNames] are kept. */
    fun evict(protectedNames: Set<String> = emptySet()): Int {
        val files = dir.listFiles()?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        var removed = 0
        for (f in files.filter { it.name !in protectedNames && !pinned.containsKey(it.name) }.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                removed++
            }
        }
        return removed
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}

/** Minimal 16-bit mono PCM WAV writer/reader (what ExoPlayer's WavExtractor plays). */
object WavWriter {
    const val HEADER_BYTES = 44L

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        file.outputStream().buffered().use { out ->
            fun int(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
            }
            fun short(v: Int) {
                out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            }
            out.write("RIFF".toByteArray()); int(36 + dataLen); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int(16); short(1); short(1); int(sampleRate); int(sampleRate * 2); short(2); short(16)
            out.write("data".toByteArray()); int(dataLen)
            for (s in samples) short((s.coerceIn(-1f, 1f) * 32767f).toInt())
        }
    }

    /** Duration of a file written by [write], from its header. */
    fun durationMs(file: File): Long {
        val header = ByteArray(HEADER_BYTES.toInt())
        file.inputStream().use { if (it.read(header) < header.size) return 0L }
        val rate = (header[24].toInt() and 0xFF) or ((header[25].toInt() and 0xFF) shl 8) or
            ((header[26].toInt() and 0xFF) shl 16) or ((header[27].toInt() and 0xFF) shl 24)
        if (rate <= 0) return 0L
        return (file.length() - HEADER_BYTES) / 2 * 1000 / rate
    }
}
