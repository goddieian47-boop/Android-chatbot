package com.example.localchat.data

import android.content.Context

enum class Backend {
    OLLAMA,
    ON_DEVICE,
}

class ChatSettings(context: Context) {
    private val preferences =
        context.getSharedPreferences("chat-settings", Context.MODE_PRIVATE)

    var backend: Backend
        get() = runCatching {
            Backend.valueOf(preferences.getString(KEY_BACKEND, Backend.OLLAMA.name)!!)
        }.getOrDefault(Backend.OLLAMA)
        set(value) {
            preferences.edit().putString(KEY_BACKEND, value.name).apply()
        }

    var ollamaUrl: String
        get() = preferences.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            preferences.edit().putString(KEY_URL, value.trimEnd('/')).apply()
        }

    var model: String
        get() = preferences.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            preferences.edit().putString(KEY_MODEL, value).apply()
        }

    var localModelPath: String?
        get() = preferences.getString(KEY_LOCAL_MODEL, null)
        set(value) {
            preferences.edit().putString(KEY_LOCAL_MODEL, value).apply()
        }

    var visionProjectorPath: String?
        get() = preferences.getString(KEY_VISION_PROJECTOR, null)
        set(value) {
            preferences.edit().putString(KEY_VISION_PROJECTOR, value).apply()
        }

    var currentConversationId: String?
        get() = preferences.getString(KEY_CONVERSATION, null)
        set(value) {
            preferences.edit().putString(KEY_CONVERSATION, value).apply()
        }

    companion object {
        const val DEFAULT_URL = "http://192.168.1.10:11434"
        const val DEFAULT_MODEL = "llama3.2"
        private const val KEY_BACKEND = "backend"
        private const val KEY_URL = "ollama_url"
        private const val KEY_MODEL = "model"
        private const val KEY_LOCAL_MODEL = "local_model_path"
        private const val KEY_VISION_PROJECTOR = "vision_projector_path"
        private const val KEY_CONVERSATION = "conversation_id"
    }
}
