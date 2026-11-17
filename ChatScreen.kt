package com.example.localchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.localchat.data.Backend
import com.example.localchat.data.MessageEntity

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onChooseModel: () -> Unit,
    onChooseProjector: () -> Unit,
    onChooseImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onVoiceInput: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val currentTitle = state.conversations
        .firstOrNull { it.id == state.selectedConversationId }
        ?.title ?: "Chat history"

    LaunchedEffect(state.messages.size, state.isLoading) {
        val lastItem = state.messages.size - if (state.isLoading) 0 else 1
        if (lastItem >= 0) listState.animateScrollToItem(lastItem)
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Local Chat", style = MaterialTheme.typography.headlineSmall)
                Text(currentTitle, style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                var menuExpanded by remember { mutableStateOf(false) }
                TextButton(onClick = { menuExpanded = true }) { Text("History") }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    state.conversations.forEach { conversation ->
                        DropdownMenuItem(
                            text = { Text(conversation.title) },
                            onClick = {
                                viewModel.selectConversation(conversation.id)
                                menuExpanded = false
                            },
                        )
                    }
                }
                TextButton(onClick = viewModel::createConversation) { Text("New chat") }
                TextButton(onClick = onOpenBrowser) { Text("Browser") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.backend == Backend.OLLAMA,
                onClick = { viewModel.setBackend(Backend.OLLAMA) },
                label = { Text("Ollama on Wi-Fi") },
            )
            FilterChip(
                selected = state.backend == Backend.ON_DEVICE,
                onClick = { viewModel.setBackend(Backend.ON_DEVICE) },
                label = { Text("On device") },
            )
        }

        if (state.backend == Backend.OLLAMA) {
            OutlinedTextField(
                value = state.endpoint,
                onValueChange = viewModel::updateEndpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ollama URL (same Wi-Fi)") },
                placeholder = { Text("http://192.168.1.25:11434") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        }

        if (state.backend == Backend.ON_DEVICE) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.localModelPath?.substringAfterLast('/') ?: "No GGUF model selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onChooseModel, enabled = !state.isImportingModel) {
                    Text(if (state.isImportingModel) "Copying..." else "Choose GGUF")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = state.visionProjectorPath?.substringAfterLast('/')
                        ?: "No mmproj selected (OCR only)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onChooseProjector, enabled = !state.isImportingModel) {
                    Text("Choose mmproj")
                }
            }
            Text(
                "For local image questions, choose the text GGUF and its matching mmproj GGUF. Both are copied into app storage.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::updateModel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ollama model") },
                singleLine = true,
            )
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = MessageEntity::id) { message ->
                MessageBubble(message)
            }
            if (state.isLoading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        Text("  Generating...")
                    }
                }
            }
        }

        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        if (state.pendingImagePath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Image attached: ${state.pendingImagePath?.substringAfterLast('/').orEmpty()}")
                    if (state.pendingImageOcr.isNotBlank()) {
                        Text(
                            "OCR: ${state.pendingImageOcr.take(400)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("No text recognized; vision models can still analyze it.")
                    }
                }
                TextButton(onClick = viewModel::clearPendingImage) { Text("Remove") }
            }
        }
        if (state.isImportingImage) {
            Text("Reading image and recognizing text...")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onChooseImage,
                enabled = !state.isLoading && !state.isImportingImage,
            ) { Text("Image") }
            TextButton(
                onClick = onTakePhoto,
                enabled = !state.isLoading && !state.isImportingImage,
            ) { Text("Camera") }
            TextButton(
                onClick = onVoiceInput,
                enabled = !state.isLoading && !state.isImportingImage,
            ) { Text("Speak") }
            TextButton(onClick = onOpenBrowser) { Text("Browse") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    viewModel.sendMessage(draft)
                    draft = ""
                },
                enabled = !state.isLoading && !state.isImportingImage &&
                    (draft.isNotBlank() || state.pendingImagePath != null),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Text(
            if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        if (message.imagePath != null) {
            Text("Image attached", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            text = message.content,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 2.dp)
                .padding(10.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
