package dev.chaichai.mobile.platform.subtitles

import java.io.File

/**
 * The subtitle formats this milestone can activate, and their MIME types for the player. A candidate
 * whose format is not here is INCOMPATIBLE: selecting it is contained (the prior subtitle stays active)
 * rather than handed to the player. Pure and JVM-testable.
 */
object SubtitleFormats {
    private val mimeByFormat: Map<String, String> = mapOf(
        "srt" to "application/x-subrip",
        "subrip" to "application/x-subrip",
        "vtt" to "text/vtt",
        "webvtt" to "text/vtt",
        "ass" to "text/x-ssa",
        "ssa" to "text/x-ssa",
    )

    fun isSupported(format: String): Boolean = mimeByFormat.containsKey(normalize(format))

    /** The player MIME type for [format], or null when the format is not supported. */
    fun mimeTypeOf(format: String): String? = mimeByFormat[normalize(format)]

    fun fileExtensionOf(format: String): String = when (normalize(format)) {
        "vtt", "webvtt" -> "vtt"
        "ass" -> "ass"
        "ssa" -> "ssa"
        else -> "srt"
    }

    private fun normalize(format: String): String = format.trim().lowercase().removePrefix(".")
}

/**
 * Persists downloaded subtitle bytes to local storage and returns a stable local reference (a file URI)
 * the player can read. Kept behind an interface so the coordinator stays JVM-testable with a temp-dir
 * implementation. Font-file upload is explicitly OUT of scope; this only stores fetched subtitle bytes.
 */
interface SubtitleDownloadStore {
    /** Write [bytes] for [candidateId] with [extension]; returns a local file-URI reference. */
    fun store(candidateId: String, extension: String, bytes: ByteArray): String
}

/** File-backed store writing under [directory] (e.g. the app cache dir). */
class FileSubtitleDownloadStore(private val directory: File) : SubtitleDownloadStore {
    override fun store(candidateId: String, extension: String, bytes: ByteArray): String {
        if (!directory.exists()) directory.mkdirs()
        val safeName = candidateId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(directory, "subtitle_$safeName.$extension")
        file.writeBytes(bytes)
        return file.toURI().toString()
    }
}
