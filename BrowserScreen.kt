package com.example.localchat.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONException
import org.json.JSONArray

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    onSendPageText: (String) -> Unit,
) {
    val context = LocalContext.current
    var address by remember { mutableStateOf("https://") }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("Back to chat") }
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                label = { Text("Web address") },
                singleLine = true,
            )
            Button(onClick = {
                val entered = address.trim().let {
                    if ("://" in it) it else "https://$it"
                }
                val uri = Uri.parse(entered)
                if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                    error = "Enter a valid HTTP or HTTPS address."
                } else {
                    error = null
                    address = entered
                    webView?.loadUrl(entered)
                }
            }) {
                Text("Go")
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = {
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = request.url.scheme !in setOf("http", "https")

                        override fun onPageFinished(view: WebView, url: String) {
                            address = url
                        }
                    }
                    webView = this
                }
            },
            update = { webView = it },
        )

        Button(
            onClick = {
                webView?.evaluateJavascript(
                    "(function(){return document.body ? document.body.innerText : '';})()",
                ) { encodedText ->
                    val pageText = try {
                        JSONArray("[$encodedText]").getString(0)
                    } catch (exception: JSONException) {
                        error = "Could not read page text: ${exception.message ?: "invalid page response"}"
                        return@evaluateJavascript
                    }
                    if (pageText.isBlank()) {
                        error = "This page has no readable text to share."
                    } else {
                        onSendPageText(
                            "Page: ${webView?.url.orEmpty()}\n\n${pageText.take(MAX_PAGE_TEXT)}",
                        )
                    }
                }
            },
            enabled = webView != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send page text to chat")
        }
    }
}

private const val MAX_PAGE_TEXT = 12_000
