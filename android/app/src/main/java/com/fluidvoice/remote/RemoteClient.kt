package com.fluidvoice.remote

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.util.UUID

class RemoteClient {
    private val socketFactories = mutableMapOf<String, SSLSocketFactory>()
    private val pinnedHostnameVerifier = HostnameVerifier { _, _ -> true }

    fun pair(payload: PairingPayload, deviceId: String, deviceName: String): CredentialStore.Connection {
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "REMOTE_BENCH phase=pair_start instance=${payload.instanceId}")
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
        Log.i(TAG, "REMOTE_BENCH phase=pair_done elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
        return CredentialStore.Connection(payload.instanceId, payload.baseUrl, payload.certificateSha256, credential)
    }

    fun dictate(connection: CredentialStore.Connection, audio: ByteArray, enhance: Boolean): String {
        val requestId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "REMOTE_BENCH id=$requestId phase=network_start bytes=${audio.size} format=m4a enhance=$enhance")
        val response = request(connection.baseUrl, connection.fingerprint, "/remote/v1/dictate", audio) {
            setRequestProperty("Content-Type", "audio/mp4")
            setRequestProperty("Authorization", "Bearer ${connection.credential}")
            setRequestProperty("X-FluidVoice-Enhance", enhance.toString())
            setRequestProperty("X-Request-ID", requestId)
        }
        Log.i(TAG, "REMOTE_BENCH id=$requestId phase=network_done elapsedMs=${SystemClock.elapsedRealtime() - startedAt} bytes=${response.size}")
        return JSONObject(String(response)).getString("finalText")
    }

    fun preconnect(connection: CredentialStore.Connection) {
        val requestId = UUID.randomUUID().toString()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "REMOTE_BENCH id=$requestId phase=preconnect_start")
        request(connection.baseUrl, connection.fingerprint, "/remote/v1/preconnect", ByteArray(0)) {
            setRequestProperty("Authorization", "Bearer ${connection.credential}")
            setRequestProperty("X-Request-ID", requestId)
        }
        Log.i(TAG, "REMOTE_BENCH id=$requestId phase=preconnect_done elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
    }

    private fun request(
        baseUrl: String,
        fingerprint: String,
        path: String,
        body: ByteArray,
        configure: HttpsURLConnection.() -> Unit,
    ): ByteArray {
        val connection = URL(baseUrl + path).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = socketFactory(fingerprint)
        connection.hostnameVerifier = pinnedHostnameVerifier
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 180_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        connection.configure()
        connection.outputStream.use { it.write(body) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.use { it.readBytes() }
        check(connection.responseCode in 200..299) { JSONObject(String(response)).optString("error", "Request failed") }
        return response
    }

    private fun socketFactory(fingerprint: String): SSLSocketFactory = synchronized(socketFactories) {
        socketFactories.getOrPut(fingerprint) {
            val trustManager = PinnedTrustManager(fingerprint)
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }.socketFactory
        }
    }

    private companion object {
        const val TAG = "FluidVoiceRemote"
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
