package com.devvalor.howyoudoing.core.presentation.util

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Text for the screen, translated when it's shown. Arguments may be [UiText] themselves (e.g. "You: [Photo]"). */
sealed interface UiText {
    data class DynamicString(val value: String) : UiText

    class Resource(
        val id: StringResource,
        val args: Array<Any> = arrayOf()
    ) : UiText {
        override fun equals(other: Any?) = other is Resource && other.id == id && other.args.contentEquals(args)
        override fun hashCode() = 31 * id.hashCode() + args.contentHashCode()
    }

    class Plural(
        val id: PluralStringResource,
        val quantity: Int,
        val args: Array<Any> = arrayOf(quantity)
    ) : UiText {
        override fun equals(other: Any?) =
            other is Plural && other.id == id && other.quantity == quantity && other.args.contentEquals(args)
        override fun hashCode() = 31 * (31 * id.hashCode() + quantity) + args.contentHashCode()
    }

    @Composable
    fun asString(): String {
        return when (this) {
            is DynamicString -> value
            is Resource -> stringResource(resource = id, *args.resolve())
            is Plural -> pluralStringResource(resource = id, quantity = quantity, *args.resolve())
        }
    }

    suspend fun asStringAsync(): String {
        return when (this) {
            is DynamicString -> value
            is Resource -> getString(resource = id, *args.resolveAsync())
            is Plural -> getPluralString(resource = id, quantity = quantity, *args.resolveAsync())
        }
    }
}

@Composable
private fun Array<Any>.resolve(): Array<Any> = Array(size) { i -> (this[i] as? UiText)?.asString() ?: this[i] }

private suspend fun Array<Any>.resolveAsync(): Array<Any> {
    val resolved = copyOf()
    for (i in indices) (this[i] as? UiText)?.let { resolved[i] = it.asStringAsync() }
    @Suppress("UNCHECKED_CAST")
    return resolved as Array<Any>
}

/** Plain text, e.g. a message someone wrote. */
fun String.asUiText(): UiText = UiText.DynamicString(this)
