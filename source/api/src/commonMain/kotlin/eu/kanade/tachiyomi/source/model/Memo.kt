package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The empty memo, which is what everything has until a source puts something in it. */
val NO_MEMO = JsonObject(emptyMap())

/**
 * [SChapter.memo] / [SManga.memo] as the app stores it.
 *
 * It is the source's own opaque blob, so it is kept verbatim rather than modelled: the app has no
 * business knowing what a source put in there, only that the source gets back what it left.
 */
fun JsonObject.memoToString(): String = if (isEmpty()) "{}" else toString()

/** The inverse of [memoToString]. Anything unreadable reads back as no memo at all. */
fun String.toMemo(): JsonObject = try {
    Json.parseToJsonElement(this).jsonObject
} catch (_: Exception) {
    NO_MEMO
}
