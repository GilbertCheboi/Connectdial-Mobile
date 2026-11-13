package org.linphone.ui.assistant.fragment

import java.net.URL
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal object ConnectDialApi {
    class HttpException(val status: Int, message: String) : java.io.IOException(message)

    fun request(path: String, body: JSONObject? = null, token: String? = null, billing: Boolean = false): JSONObject {
        require(!path.startsWith("/") && !path.contains("://") && !path.contains(".."))
        val connection = URL("https://call.agrieldo.com/api/v1/$path").openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "${if (billing) "Billing" else "Bearer"} $it") }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val data = if (text.isBlank()) {
                JSONObject()
            } else {
                try {
                    JSONObject(text)
                } catch (_: org.json.JSONException) {
                    throw java.io.IOException("Unable to reach ConnectDial. Please try again.")
                }
            }
            if (code !in 200..299) {
                val detail = data.optString("message", data.optString("detail", ""))
                throw HttpException(
                    code,
                    detail.ifBlank {
                    "Request could not be completed. Check your details and sign in again if your session expired."
                }
                )
            }
            return data
        } catch (error: SocketTimeoutException) {
            throw java.io.IOException("Cannot reach ConnectDial. Check your internet connection or try Wi-Fi, then sign in again.", error)
        } catch (error: UnknownHostException) {
            throw java.io.IOException("Cannot find ConnectDial. Check your internet connection, then sign in again.", error)
        } catch (error: ConnectException) {
            throw java.io.IOException("Cannot connect to ConnectDial. Check your internet connection or try Wi-Fi, then sign in again.", error)
        } finally {
            connection.disconnect()
        }
    }
}
