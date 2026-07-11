package com.fluidvoice.remote

import org.json.JSONObject

data class PairingPayload(
    val version: Int,
    val baseUrl: String,
    val instanceId: String,
    val pairingSecret: String,
    val certificateSha256: String,
) {
    companion object {
        fun parse(value: String): PairingPayload {
            val json = JSONObject(value)
            require(json.getInt("version") == 1) { "Unsupported pairing version" }
            val baseUrl = json.getString("baseURL")
            require(baseUrl.startsWith("https://")) { "Pairing URL must use HTTPS" }
            return PairingPayload(
                version = 1,
                baseUrl = baseUrl.removeSuffix("/"),
                instanceId = json.getString("instanceID"),
                pairingSecret = json.getString("pairingSecret"),
                certificateSha256 = json.getString("certificateSHA256").lowercase(),
            )
        }
    }
}
