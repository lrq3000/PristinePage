package app.pristinepage.data.journal

import app.pristinepage.core.pipeline.requireSha256
import app.pristinepage.core.library.LibraryScanName
import app.pristinepage.data.model.IngestJournalIntent
import app.pristinepage.data.model.IngestJournalSeal
import app.pristinepage.data.model.IngestJournalCommit
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Canonical UTF-8 JSON. V2 adds a capture-date projection; v3 carries requested and allocated logical filenames without changing the intent, v1 or v2 bytes. Reject noncanonical encodings before recovery consumes them. */
object IngestJournalCodec {
    private const val MAX_BYTES = 524288
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @JvmStatic fun encodeIntent(intent: IngestJournalIntent): ByteArray {
        validate(intent)
        return encode(IngestJournalIntent.serializer(), intent)
    }

    @JvmStatic fun decodeIntent(bytes: ByteArray): IngestJournalIntent = decode(IngestJournalIntent.serializer(), bytes).also(::validate)

    @JvmStatic fun encodeSeal(seal: IngestJournalSeal): ByteArray {
        validate(seal)
        return encode(IngestJournalSeal.serializer(), seal)
    }

    @JvmStatic fun decodeSeal(bytes: ByteArray, expected: IngestJournalIntent): IngestJournalSeal {
        validate(expected)
        val seal = decode(IngestJournalSeal.serializer(), bytes)
        validate(seal)
        require(seal.intent == expected) { "Ingest seal ownership mismatch" }
        return seal
    }

    @JvmStatic fun encodeCommit(commit: IngestJournalCommit): ByteArray {
        validate(commit.seal)
        require(commit.schemaVersion == commit.seal.schemaVersion && commit.documentRevision >= 1) { "Unsupported ingest commit" }
        require((commit.schemaVersion == 3) == (commit.libraryFilename != null))
        commit.libraryFilename?.let(LibraryScanName::comparisonKey)
        return encode(IngestJournalCommit.serializer(), commit)
    }

    @JvmStatic fun decodeCommit(bytes: ByteArray, expected: IngestJournalIntent): IngestJournalCommit {
        validate(expected)
        val commit = decode(IngestJournalCommit.serializer(), bytes)
        encodeCommit(commit)
        require(commit.seal.intent == expected) { "Ingest commit ownership mismatch" }
        return commit
    }

    private fun validate(intent: IngestJournalIntent) {
        require(intent.schemaVersion == 1 && intent.attempt >= 1) { "Unsupported ingest intent" }
    }

    private fun validate(seal: IngestJournalSeal) {
        validate(seal.intent)
        val supported = when (seal.schemaVersion) {
            1 -> seal.captureDate == null && seal.preferredFilename == null
            2 -> seal.captureDate != null && seal.preferredFilename == null
            3 -> true
            else -> false
        }
        require(supported && seal.byteCount in 1..104857600L) { "Unsupported ingest seal" }
        seal.preferredFilename?.let(LibraryScanName::comparisonKey)
        requireSha256(seal.sourceByteHash)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray {
        val text = canonical(json.encodeToJsonElement(serializer, value)).toString()
        require(text.length <= MAX_BYTES) { "Ingest metadata exceeds limit" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        require(encoded.remaining() <= MAX_BYTES) { "Ingest metadata exceeds limit" }
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Invalid ingest metadata length" }
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            checkDepth(text)
            val value = json.decodeFromString(serializer, text)
            // Byte equality also rejects duplicates (including escaped-equivalent keys), omitted defaults, alternate numbers, extra whitespace and noncanonical order before any recovery command can consume the value.
            require(bytes.contentEquals(encode(serializer, value)))
            return value
        } catch (_: Exception) {
            // Serializer diagnostics can contain source text. The public error carries neither private metadata nor parser excerpts.
            throw IllegalArgumentException("Invalid or unsupported ingest metadata")
        }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    /** Bound parser recursion before typed decoding; braces inside strings do not add nesting. Syntax and matching bracket types are still checked by the serializer. */
    private fun checkDepth(text: String) {
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
    }
}
