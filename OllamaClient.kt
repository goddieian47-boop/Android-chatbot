package com.example.localchat.inference

import com.example.localchat.data.MessageEntity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

class OllamaClient {
    fun chat(
        endpoint: String,
        model: String,
        messages: List<MessageEntity>,
    ): String {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().apply {
                    messages.forEach { message ->
                        val item = JSONObject()
                            .put("role", message.role)
                            .put("content", message.content)
                        message.imagePath?.let { path ->
                            val image = File(path)
                            check(image.isFile) { "A saved chat image is missing." }
                            item.put(
                                "images",
                                JSONArray().put(Base64.getEncoder().encodeToString(image.readBytes())),
                            )
                        }
                        put(item)
                    }
                },
            )
            .put("stream", false)

        val connection = URL("${endpoint.trimEnd('/')}/api/chat")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }

            val status = connection.responseCode
            val responseStream =
                if (status in 200..299) connection.inputStream else connection.errorStream
            val response = responseStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optString("error") }
                    .getOrDefault(response)
                error("Ollama returned HTTP $status: $detail")
            }

            val content = JSONObject(response)
                .optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            check(content.isNotBlank()) { "Ollama returned an empty response." }
            return content
        } finally {
            connection.disconnect()
        }
    }
}
