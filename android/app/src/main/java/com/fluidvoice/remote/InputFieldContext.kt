package com.fluidvoice.remote

import java.util.Base64
import org.json.JSONObject

internal data class InputFieldContext(
    val label: String?,
    val placeholder: String?,
)

internal fun buildInputFieldContext(
    nodeText: CharSequence?,
    isShowingHintText: Boolean,
    isPassword: Boolean,
    hintText: CharSequence?,
    labeledByText: CharSequence?,
    contentDescription: CharSequence?,
): InputFieldContext? {
    if (isPassword) return null
    val placeholder = (hintText ?: nodeText.takeIf { isShowingHintText }).contextValue()
    val label = sequenceOf(labeledByText, contentDescription)
        .mapNotNull { it.contextValue() }
        .firstOrNull { !it.equals(placeholder, ignoreCase = true) }
    return if (label == null && placeholder == null) null else InputFieldContext(label, placeholder)
}

internal fun encodeInputFieldContextHeader(context: InputFieldContext?, enhance: Boolean): String? {
    if (!enhance || context == null) return null
    val json = JSONObject().apply {
        context.label?.let { put("label", it) }
        context.placeholder?.let { put("placeholder", it) }
    }
    return Base64.getEncoder().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
}

private fun CharSequence?.contextValue(): String? = this
    ?.toString()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.take(MAX_CONTEXT_VALUE_LENGTH)
    ?.takeIf { it.isNotEmpty() }

private const val MAX_CONTEXT_VALUE_LENGTH = 200
