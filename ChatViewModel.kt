package com.example.localchat.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.localchat.data.Backend
import com.example.localchat.data.ChatDatabase
import com.example.localchat.data.ChatRepository
import com.example.localchat.data.ChatSettings
import com.example.localchat.data.ConversationEntity
import com.example.localchat.data.MessageEntity
import com.example.localchat.inference.ImageProcessor
import com.example.localchat.inference.LocalLlamaEngine
import com.example.localchat.inference.OllamaClient
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val backend: Backend = Backend.OLLAMA,
    val endpoint: String = ChatSettings.DEFAULT_URL,
    val model: String = ChatSettings.DEFAULT_MODEL,
    val localModelPath: String? = null,
    val visionProjectorPath: String? = null,
    val pendingImagePath: String? = null,
    val pendingImageOcr: String = "",
    val conversations: List<ConversationEntity> = emptyList(),
    val selectedConversationId: String? = null,
    val messages: List<MessageEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isImportingModel: Boolean = false,
    val isImportingImage: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = ChatDatabase.create(application)
    private val repository = ChatRepository(database.chatDao())
    private val settings = ChatSettings(application)
    private val ollama = OllamaClient()
    private val localEngine = LocalLlamaEngine()
    private val imageProcessor = ImageProcessor(application)
    private val selectedId = MutableStateFlow<String?>(settings.currentConversationId)
    private val _speechReplies = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val speechReplies: SharedFlow<String> = _speechReplies
    private val _uiState = MutableStateFlow(
        ChatUiState(
            backend = settings.backend,
            endpoint = settings.ollamaUrl,
            model = settings.model,
            localModelPath = settings.localModelPath,
            visionProjectorPath = settings.visionProjectorPath,
            selectedConversationId = selectedId.value,
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            selectedId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList<MessageEntity>()) else repository.messages(id)
            }.collectLatest { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
        viewModelScope.launch {
            val savedConversation = settings.currentConversationId
                ?.let { repository.getConversation(it) }
            val initialConversation = savedConversation
                ?: repository.getNewestConversation()
                ?: repository.createConversation().let { repository.getConversation(it) }
            initialConversation?.let { selectConversation(it.id) }
        }
        viewModelScope.launch {
            repository.conversations().collectLatest { conversations ->
                _uiState.value = _uiState.value.copy(conversations = conversations)
                val current = selectedId.value
                if (current != null && conversations.none { it.id == current }) {
                    conversations.firstOrNull()?.let { selectConversation(it.id) }
                }
            }
        }
    }

    fun selectConversation(id: String) {
        settings.currentConversationId = id
        selectedId.value = id
        _uiState.value = _uiState.value.copy(selectedConversationId = id, error = null)
    }

    fun createConversation() {
        viewModelScope.launch {
            selectConversation(repository.createConversation())
        }
    }

    fun setBackend(backend: Backend) {
        settings.backend = backend
        _uiState.value = _uiState.value.copy(backend = backend, error = null)
    }

    fun updateEndpoint(endpoint: String) {
        settings.ollamaUrl = endpoint
        _uiState.value = _uiState.value.copy(endpoint = endpoint)
    }

    fun updateModel(model: String) {
        settings.model = model
        _uiState.value = _uiState.value.copy(model = model)
    }

    fun importModel(uri: Uri, isVisionProjector: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingModel = true, error = null)
            try {
                val path = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst() && nameColumn >= 0) cursor.getString(nameColumn) else null
                    } ?: "model.gguf"
                    val directory = File(getApplication<Application>().filesDir, "models")
                        .apply { mkdirs() }
                    val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val destination = File(directory, "${UUID.randomUUID()}-$safeName")
                    val input = resolver.openInputStream(uri)
                        ?: error("Could not open the selected model file.")
                    try {
                        input.use { source ->
                            destination.outputStream().buffered().use { output ->
                                source.copyTo(output)
                            }
                        }
                    } catch (exception: Exception) {
                        destination.delete()
                        throw exception
                    }
                    destination.absolutePath
                }
                if (isVisionProjector) {
                    settings.visionProjectorPath = path
                    _uiState.value = _uiState.value.copy(visionProjectorPath = path)
                } else {
                    settings.localModelPath = path
                    _uiState.value = _uiState.value.copy(localModelPath = path)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "Could not import the model file.",
                )
            } finally {
                _uiState.value = _uiState.value.copy(isImportingModel = false)
            }
        }
    }

    fun attachImage(uri: Uri, sourceFileToDelete: File? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImportingImage = true, error = null)
            try {
                val (path, recognizedText) = imageProcessor.importAndRecognize(uri)
                val oldPath = _uiState.value.pendingImagePath
                if (oldPath != null) {
                    withContext(Dispatchers.IO) { File(oldPath).delete() }
                }
                _uiState.value = _uiState.value.copy(
                    pendingImagePath = path,
                    pendingImageOcr = recognizedText,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "Could not read the selected image.",
                )
            } finally {
                sourceFileToDelete?.delete()
                _uiState.value = _uiState.value.copy(isImportingImage = false)
            }
        }
    }

    fun clearPendingImage() {
        val path = _uiState.value.pendingImagePath
        _uiState.value = _uiState.value.copy(pendingImagePath = null, pendingImageOcr = "")
        if (path != null) {
            viewModelScope.launch(Dispatchers.IO) { File(path).delete() }
        }
    }

    fun setError(message: String) {
        _uiState.value = _uiState.value.copy(error = message)
    }

    fun sendMessage(text: String, speakResponse: Boolean = false) {
        val typedPrompt = text.trim()
        val conversationId = selectedId.value ?: return
        val currentState = _uiState.value
        if (typedPrompt.isEmpty() && currentState.pendingImagePath == null) return
        if (currentState.isLoading || currentState.isImportingImage) return
        val backend = settings.backend
        val endpoint = settings.ollamaUrl
        val model = settings.model
        val localModelPath = settings.localModelPath
        val visionProjectorPath = settings.visionProjectorPath
        val imagePath = currentState.pendingImagePath
        val imageOcr = currentState.pendingImageOcr
        val prompt = typedPrompt.ifEmpty { "Describe the attached image." }
        val content = buildString {
            append(prompt)
            if (imageOcr.isNotBlank()) {
                append("\n\nText recognized in the image:\n")
                append(imageOcr.take(MAX_OCR_CHARS))
            }
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                pendingImagePath = null,
                pendingImageOcr = "",
            )
            try {
                repository.addMessage(conversationId, "user", content, imagePath)
                val messages = withContext(Dispatchers.IO) {
                    repository.getMessages(conversationId)
                }
                val reply = withContext(Dispatchers.IO) {
                    when (backend) {
                        Backend.OLLAMA -> ollama.chat(endpoint, model, messages)
                        Backend.ON_DEVICE -> {
                            val path = localModelPath
                                ?: error("Choose a GGUF model before sending a message.")
                            localEngine.chat(path, visionProjectorPath, messages)
                        }
                    }
                }
                repository.addMessage(conversationId, "assistant", reply)
                if (speakResponse) _speechReplies.emit(reply)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "The request failed.",
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    override fun onCleared() {
        imageProcessor.close()
        database.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_OCR_CHARS = 4_000
    }
}
