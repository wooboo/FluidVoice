package com.fluidvoice.remote

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RemoteClient {
    fun pair(payload: PairingPayload, deviceId: String, deviceName: String): CredentialStore.Connection {
        val body = JSONObject()
            .put("deviceID", deviceId)
            .put("deviceName", deviceName)
            .put("pairingSecret", payload.pairingSecret)
            .toString()
            .toByteArray()
        val response = request(payload.baseUrl, payload.certificateSha256, "/remote/v1/pair", body) {
            setRequestProperty("Content-Type", "application/json")
        }
        val credential = JSONObject(String(response)).getString("credential")
        return CredentialStore.Connection(payload.instanceId, payload.baseUrl, payload.certificateSha256, credential)
    }

    fun dictate(connection: CredentialStore.Connection, wav: ByteArray): String {
        val response = request(connection.baseUrl, connection.fingerprint, "/remote/v1/dictate", wav) {
            setRequestProperty("Content-Type", "audio/wav")
            setRequestProperty("Authorization", "Bearer ${connection.credential}")
            setRequestProperty("X-FluidVoice-Enhance", "true")
        }
        return JSONObject(String(response)).getString("finalText")
    }

    private fun request(
        baseUrl: String,
        fingerprint: String,
        path: String,
        body: ByteArray,
        configure: HttpsURLConnection.() -> Unit,
    ): ByteArray {
        val trustManager = PinnedTrustManager(fingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        val connection = URL(baseUrl + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 180_000
        connection.doOutput = true
        connection.configure()
        connection.outputStream.use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.use { it.readBytes() }
        check(connection.responseCode in 200..299) { JSONObject(String(response)).optString("error", "Request failed") }
        return response
    }
}

private class PinnedTrustManager(expectedFingerprint: String) : X509TrustManager {
    private val expected = expectedFingerprint.lowercase()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing server certificate")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
            throw java.security.cert.CertificateException("FluidVoice certificate does not match pairing QR")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
