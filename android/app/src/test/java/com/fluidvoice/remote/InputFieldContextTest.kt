package com.fluidvoice.remote

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputFieldContextTest {
    @Test
    fun `field context prefers explicit accessibility label and hint`() {
        val context = buildInputFieldContext(
            nodeText = "Search people",
            isShowingHintText = true,
            isPassword = false,
            hintText = "Search people",
            labeledByText = "Recipients",
            contentDescription = "Recipient search",
        )

        assertEquals(InputFieldContext(label = "Recipients", placeholder = "Search people"), context)
    }

    @Test
    fun `field context does not expose entered text as metadata`() {
        val context = buildInputFieldContext(
            nodeText = "private draft",
            isShowingHintText = false,
            isPassword = false,
            hintText = null,
            labeledByText = null,
            contentDescription = null,
        )

        assertNull(context)
    }

    @Test
    fun `context header is sent only for AI enhancement`() {
        val context = InputFieldContext(label = "Message", placeholder = "Write a reply")

        assertNull(encodeInputFieldContextHeader(context, enhance = false))

        val encoded = encodeInputFieldContextHeader(context, enhance = true)!!
        val decoded = JSONObject(String(Base64.getDecoder().decode(encoded)))
        assertEquals("Message", decoded.getString("label"))
        assertEquals("Write a reply", decoded.getString("placeholder"))
    }

    @Test
    fun `duplicate label and placeholder are not sent twice`() {
        val context = buildInputFieldContext(
            nodeText = "Add comment",
            isShowingHintText = true,
            isPassword = false,
            hintText = "Add comment",
            labeledByText = null,
            contentDescription = "Add comment",
        )

        assertEquals(InputFieldContext(label = null, placeholder = "Add comment"), context)
    }

    @Test
    fun `password fields never expose destination metadata`() {
        val context = buildInputFieldContext(
            nodeText = "Password",
            isShowingHintText = true,
            isPassword = true,
            hintText = "Password",
            labeledByText = "Bank password",
            contentDescription = "Enter your bank password",
        )

        assertNull(context)
    }
}
