package app.pristinepage.core.recipe

import app.pristinepage.core.pipeline.Recipe
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** recipe-json-v1: strict typed UTF-8 JSON, lexicographically sorted object keys, explicit effective defaults/nulls, stable discriminators. Arrays preserve semantic order. */
object RecipeCodec {
    private const val MAX_BYTES = 524288
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "kind" }

    fun encode(recipe: Recipe): ByteArray {
        val element = json.encodeToJsonElement(Recipe.serializer(), recipe)
        val text = canonical(element).toString()
        require(text.length <= MAX_BYTES) { "Recipe exceeds bounded metadata size" }
        val result = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Recipe contains ill-formed Unicode", error)
        }
        require(result.size <= MAX_BYTES) { "Recipe exceeds bounded metadata size" }
        return result
    }

    fun decode(bytes: ByteArray): Recipe {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Invalid recipe byte count" }
        try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            rejectAmbiguousStructure(text)
            return json.decodeFromString(Recipe.serializer(), text)
        } catch (error: Exception) {
            throw IllegalArgumentException("Unsupported or invalid recipe", error)
        }
    }

    /** Tagged, length-delimited bytes distinguish this hash from source-pixel/file hashes; no timestamp/runtime timing enters recipe identity. */
    fun hash(recipe: Recipe): String {
        val bytes = encode(recipe)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("pristinepage-recipe-json-v1\u0000".toByteArray(Charsets.UTF_8))
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }

    /** The JSON backend otherwise accepts duplicate keys with last-value-wins semantics. Inspect structure before typed decoding, including escaped equivalent keys, with a bounded nesting depth. Syntax remains the backend's responsibility. */
    private fun rejectAmbiguousStructure(text: String) {
        val stack = ArrayDeque<MutableSet<String>?>()
        var position = 0
        while (position < text.length) {
            when (text[position]) {
                '{', '[' -> {
                    stack.addLast(if (text[position] == '{') mutableSetOf() else null)
                    require(stack.size <= 64) { "Recipe nesting exceeds limit" }
                    position++
                }
                '}', ']' -> {
                    require(stack.isNotEmpty())
                    require((text[position] == '}') == (stack.last() != null))
                    stack.removeLast()
                    position++
                }
                '"' -> {
                    val start = position++
                    while (position < text.length && text[position] != '"') {
                        if (text[position] == '\\') position++
                        position++
                    }
                    require(position < text.length) { "Unterminated JSON string" }
                    position++
                    var next = position
                    while (next < text.length && text[next].isWhitespace()) next++
                    if (next < text.length && text[next] == ':') {
                        val keys = checkNotNull(stack.lastOrNull()) { "Object key outside an object" }
                        val key = json.decodeFromString<String>(text.substring(start, position))
                        require(keys.add(key)) { "Duplicate recipe field" }
                    }
                }
                else -> position++
            }
        }
        require(stack.isEmpty()) { "Unclosed JSON structure" }
    }
}
