package com.shanekoh.fitchecker

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ShareActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @Volatile private var capturedPostImageUrl: String? = null
    @Volatile private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_SEND) { finish(); return }

        val mimeType = intent.type ?: ""

        when {
            mimeType.startsWith("image/") -> {
                val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (imageUri != null) {
                    val cacheFile = copyUriToCache(imageUri)
                    if (cacheFile != null) openResultsActivity(cacheFile) else finish()
                } else {
                    finish()
                }
            }

            mimeType == "text/plain" -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val url = extractInstagramUrl(text)
                if (url != null) {
                    Toast.makeText(this, "Finding outfit...", Toast.LENGTH_SHORT).show()
                    loadInstagramPage(url)
                } else {
                    finish()
                }
            }

            else -> finish()
        }
    }

    private fun extractInstagramUrl(text: String): String? {
        val regex = Regex("""https?://(?:www\.)?instagram\.com/(?:p|reel)/[\w-]+/?""")
        return regex.find(text)?.value
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadInstagramPage(url: String) {
        webView = WebView(this).also { setContentView(it) }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onImageFound(imageUrl: String) {
                    if (handled) return
                    handled = true
                    runOnUiThread { fetchImageThenOpen(imageUrl) }
                }

                @JavascriptInterface
                fun onNotFound() {
                    runOnUiThread {
                        Toast.makeText(this@ShareActivity, "Could not find image in post", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            }, "LensSearch")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if ((reqUrl.contains("cdninstagram") || reqUrl.contains("fbcdn")) &&
                        reqUrl.contains("-15/") &&
                        (reqUrl.contains(".jpg") || reqUrl.contains(".webp"))) {

                        val current = capturedPostImageUrl
                        val newIsHighRes = reqUrl.contains("s1080x") || reqUrl.contains("s750x") || reqUrl.contains("s640x")
                        val currentIsHighRes = current != null &&
                            (current.contains("s1080x") || current.contains("s750x") || current.contains("s640x"))

                        if (current == null || (newIsHighRes && !currentIsHighRes)) {
                            capturedPostImageUrl = reqUrl
                        }
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    view?.evaluateJavascript(JS_DISMISS_POPUP, null)
                    view?.postDelayed({
                        if (handled) return@postDelayed
                        val postImage = capturedPostImageUrl
                        if (postImage != null) {
                            handled = true
                            runOnUiThread { fetchImageThenOpen(postImage) }
                        } else {
                            view.evaluateJavascript(JS_EXTRACT_IMAGE, null)
                        }
                    }, 4000)
                }
            }

            loadUrl(url)
        }
    }

    private fun fetchImageThenOpen(imageUrl: String) {
        Thread {
            try {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0"
                )
                connection.connect()

                if (connection.responseCode == 200) {
                    val cacheFile = File(cacheDir, "fit_${System.currentTimeMillis()}.jpg")
                    cacheFile.outputStream().use { out -> connection.inputStream.copyTo(out) }
                    connection.disconnect()
                    runOnUiThread { openResultsActivity(cacheFile) }
                } else {
                    connection.disconnect()
                    runOnUiThread {
                        Toast.makeText(this, "Image download failed (${connection.responseCode})", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun openResultsActivity(imageFile: File) {
        val intent = Intent(this, ResultsActivity::class.java).apply {
            putExtra(ResultsActivity.EXTRA_IMAGE_PATH, imageFile.absolutePath)
        }
        startActivity(intent)
        finish()
    }

    private fun copyUriToCache(sourceUri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(sourceUri) ?: return null
            val cacheFile = File(cacheDir, "fit_${System.currentTimeMillis()}.jpg")
            cacheFile.outputStream().use { output -> inputStream.copyTo(output) }
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }

    companion object {
        private val JS_DISMISS_POPUP = """
            (function() {
                function dismiss() {
                    document.querySelectorAll('*').forEach(function(el) {
                        try {
                            var style = window.getComputedStyle(el);
                            var isOverlay = style.position === 'fixed' || style.position === 'sticky';
                            if (isOverlay) {
                                var text = el.innerText || '';
                                if (text.includes('Open in') || text.includes('Get the app') ||
                                    text.includes('Log in') || text.includes('Sign up')) {
                                    el.remove();
                                }
                            }
                        } catch(e) {}
                    });
                    document.querySelectorAll('[role="dialog"]').forEach(function(el) { el.remove(); });
                }
                dismiss();
                setInterval(dismiss, 1000);
            })();
        """.trimIndent()

        private val JS_EXTRACT_IMAGE = """
            (function() {
                var best = null;
                var bestArea = 0;
                document.querySelectorAll('img').forEach(function(img) {
                    if (!img.src || !img.src.includes('-15/')) return;
                    var area = img.naturalWidth * img.naturalHeight;
                    if (area > bestArea) { bestArea = area; best = img.src; }
                });
                if (best) { LensSearch.onImageFound(best); }
                else { LensSearch.onNotFound(); }
            })();
        """.trimIndent()
    }
}
