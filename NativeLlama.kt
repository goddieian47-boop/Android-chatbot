package com.example.localchat.inference

import com.example.localchat.data.MessageEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private object NativeLlama {
    init {
        System.loadLibrary("localchat")
    }

    external fun loadModel(path: String, projectorPath: String): Boolean
    external fun generate(
        roles: Array<String>,
        contents: Array<String>,
        imagePaths: Array<String>,
        maxTokens: Int,
    ): String
    external fun releaseModel()
}

class LocalLlamaEngine {
    private val mutex = Mutex()
    private var loadedKey: String? = null

    suspend fun chat(
        modelPath: String,
        projectorPath: String?,
        messages: List<MessageEntity>,
    ): String =
        mutex.withLock {
            val loadKey = "$modelPath|${projectorPath.orEmpty()}"
            if (loadedKey != loadKey) {
                NativeLlama.releaseModel()
                loadedKey = null
                check(NativeLlama.loadModel(modelPath, projectorPath.orEmpty())) {
                    "Could not load this GGUF model. Check that the file is valid and the device has enough memory."
                }
                loadedKey = loadKey
            }

            NativeLlama.generate(
                roles = messages.map(MessageEntity::role).toTypedArray(),
                contents = messages.map(MessageEntity::content).toTypedArray(),
                imagePaths = messages.map { message ->
                    if (projectorPath == null) "" else message.imagePath.orEmpty()
                }.toTypedArray(),
                maxTokens = 256,
            ).also {
                check(it.isNotBlank()) { "The on-device model returned an empty response." }
            }
        }
}
