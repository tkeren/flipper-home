package com.flipperhome

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Bridge credentials go only to the user's configured HTTPS server. */
internal suspend fun signInGoogleBridge(url: String, username: String, password: String, phoneId: String,
    client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()): String = withContext(Dispatchers.IO) {
    val socketUrl = URI(voiceSocketUrl(url, VoiceProvider.GOOGLE_HOME))
    val loginUrl = URI("https", null, socketUrl.host, socketUrl.port, socketUrl.path.removeSuffix("ws") + "login", null, null)
    require(username.isNotBlank() && password.isNotBlank()) { "Enter your bridge username and password" }
    val body = JSONObject().put("username", username.trim()).put("password", password).put("bridge_id", phoneId)
        .put("name", "${Build.MODEL} · Flipper Home".take(80)).toString().toRequestBody("application/json".toMediaType())
    client.newCall(Request.Builder().url(loginUrl.toString()).post(body).build()).execute().use { response ->
        when(response.code) {
            401 -> error("Incorrect bridge username or password")
            429 -> error("Too many attempts. Try again later")
        }
        check(response.isSuccessful) { "Bridge sign-in failed (${response.code})" }
        val text = response.body?.string().orEmpty()
        require(text.length <= 16000) { "Invalid bridge response" }
        JSONObject(text).getString("access_token").also {
            require(it.isNotBlank() && it.length <= 8192 && it.none(Char::isWhitespace)) { "Invalid bridge credentials" }
        }
    }
}
