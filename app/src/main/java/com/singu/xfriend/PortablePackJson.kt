package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal fun portableUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

/** Android's JSONTokener accepts duplicate keys and non-JSON syntax. Validate before using it. */
internal fun portableJson(bytes: ByteArray): JSONObject {
    val text = portableUtf8(bytes)
    var pos = 0
    fun ws() { while (pos < text.length && text[pos] in " \r\n\t") pos++ }
    fun string(): String {
        val start = pos
        require(text[pos++] == '"')
        while (pos < text.length) {
            val c = text[pos++]
            if (c == '"') return JSONArray("[${text.substring(start, pos)}]").getString(0)
            require(c >= ' ')
            if (c == '\\') {
                require(pos < text.length)
                val esc = text[pos++]
                require(esc in "\"\\/bfnrtu")
                if (esc == 'u') { repeat(4) { require(pos < text.length && text[pos++] in "0123456789abcdefABCDEF") } }
            }
        }
        throw InvalidPortablePack()
    }
    fun value(depth: Int): Unit {
        require(depth <= 256); ws(); require(pos < text.length)
        when (text[pos]) {
            '{', '[' -> {
                val obj = text[pos++] == '{'; val close = if (obj) '}' else ']'
                val keys = mutableSetOf<String>()
                ws()
                if (pos < text.length && text[pos] == close) { pos++; return }
                while (true) {
                    ws()
                    if (obj) { require(pos < text.length && text[pos] == '"'); require(keys.add(string())); ws(); require(pos < text.length && text[pos++] == ':') }
                    value(depth + 1); ws(); require(pos < text.length)
                    if (text[pos] == close) { pos++; break }
                    require(text[pos++] == ',')
                }
            }
            '"' -> { string() }
            't', 'f', 'n' -> {
                val literal = when (text[pos]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                require(text.startsWith(literal, pos)); pos += literal.length
            }
            else -> {
                val match = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, pos)
                require(match != null && match.range.first == pos)
                pos = match.range.last + 1
            }
        }
    }
    value(0); ws(); require(pos == text.length)
    return JSONObject(text)
}
