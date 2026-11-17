package com.example.localchat

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.localchat.ui.BrowserScreen
import com.example.localchat.ui.ChatScreen
import com.example.localchat.ui.ChatViewModel
import com.example.localchat.ui.VoiceController
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    LocalChatApp(viewModel)
                }
            }
        }
    }
}

@Composable
private fun LocalChatApp(viewModel: ChatViewModel) {
    val context = LocalContext.current
    var showingBrowser by remember { mutableStateOf(false) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val voice = remember(context) {
        VoiceController(
            context = context,
            onRecognized = { viewModel.sendMessage(it, speakResponse = true) },
            reportError = viewModel::setError,
        )
    }
    DisposableEffect(voice) {
        onDispose(voice::close)
    }
    LaunchedEffect(viewModel, voice) {
        viewModel.speechReplies.collect { voice.speak(it) }
    }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importModel(it) } },
    )
    val projectorPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importModel(it, isVisionProjector = true) } },
    )
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::attachImage) },
    )
    val camera = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { succeeded ->
            val file = captureFile
            val uri = captureUri
            if (succeeded && file != null && uri != null) {
                viewModel.attachImage(uri, sourceFileToDelete = file)
            } else {
                file?.delete()
            }
            captureFile = null
            captureUri = null
        },
    )
    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) voice.listen() else viewModel.setError("Microphone permission is required for voice input.")
        },
    )

    if (showingBrowser) {
        BrowserScreen(
            onBack = { showingBrowser = false },
            onSendPageText = { pageText ->
                viewModel.sendMessage(pageText)
                showingBrowser = false
            },
        )
    } else {
        ChatScreen(
            viewModel = viewModel,
            onChooseModel = { modelPicker.launch(arrayOf("*/*")) },
            onChooseProjector = { projectorPicker.launch(arrayOf("*/*")) },
            onChooseImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onTakePhoto = {
                val file = File(context.cacheDir, "camera-${UUID.randomUUID()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                captureFile = file
                captureUri = uri
                camera.launch(uri)
            },
            onVoiceInput = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    voice.listen()
                } else {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onOpenBrowser = { showingBrowser = true },
        )
    }
}
