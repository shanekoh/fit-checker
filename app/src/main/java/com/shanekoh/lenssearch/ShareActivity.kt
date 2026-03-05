package com.shanekoh.lenssearch

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ShareActivity : AppCompatActivity() {

    private var webView: WebView? = null

    // Shared state — post image URL captured from network interception
    @Volatile private var capturedPostImageUrl: String? = null
    @Volatile private var handled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != Intent.ACTION_SEND) { finish(); return }

        val mimeType = intent.type ?: ""

        when {
            // Image shared directly from gallery
            mimeType.startsWith("image/") -> {
                val imageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (imageUri != null) {
                    val localUri = copyToCache(imageUri) ?: imageUri
                    openGoogleLens(localUri)
                } else {
                    finish()
                }
            }

            // Instagram post URL shared as text
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
                // Called by JS fallback if network interception found nothing
                @JavascriptInterface
                fun onImageFound(imageUrl: String) {
                    if (handled) return
                    handled = true
                    runOnUiThread { fetchImageAndOpenLens(imageUrl) }
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

                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ) = false

                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null

                    // Instagram post images always contain "-15/" in the CDN path.
                    // Profile pictures use "-19/". This is a stable CDN-level signal.
                    if ((reqUrl.contains("cdninstagram") || reqUrl.contains("fbcdn")) &&
                        reqUrl.contains("-15/") &&
                        (reqUrl.contains(".jpg") || reqUrl.contains(".webp"))) {

                        val current = capturedPostImageUrl
                        // Prefer higher resolution — update if we find a better one
                        val newIsHighRes = reqUrl.contains("s1080x") || reqUrl.contains("s750x") || reqUrl.contains("s640x")
                        val currentIsHighRes = current != null &&
                            (current.contains("s1080x") || current.contains("s750x") || current.contains("s640x"))

                        if (current == null || (newIsHighRes && !currentIsHighRes)) {
                            capturedPostImageUrl = reqUrl
                        }
                    }
                    return null // Never block, just observe
                }

                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    // Dismiss Instagram's "Open in app" popup immediately
                    view?.evaluateJavascript(JS_DISMISS_POPUP, null)

                    // Wait for lazy-loaded images to finish, then use what we captured
                    view?.postDelayed({
                        if (handled) return@postDelayed

                        val postImage = capturedPostImageUrl
                        if (postImage != null) {
                            handled = true
                            runOnUiThread { fetchImageAndOpenLens(postImage) }
                        } else {
                            // Fallback: use JS to find any -15/ image in the DOM
                            view.evaluateJavascript(JS_EXTRACT_IMAGE, null)
                        }
                    }, 4000)
                }
            }

            loadUrl(url)
        }
    }

    private fun fetchImageAndOpenLens(imageUrl: String) {
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
                    val cacheFile = File(cacheDir, "lens_${System.currentTimeMillis()}.jpg")
                    cacheFile.outputStream().use { out -> connection.inputStream.copyTo(out) }
                    connection.disconnect()

                    val uri = FileProvider.getUriForFile(
                        this, "${packageName}.fileprovider", cacheFile
                    )
                    runOnUiThread { openGoogleLens(uri) }
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

    private fun copyToCache(sourceUri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(sourceUri) ?: return null
            val cacheFile = File(cacheDir, "lens_${System.currentTimeMillis()}.jpg")
            cacheFile.outputStream().use { output -> inputStream.copyTo(output) }
            FileProvider.getUriForFile(this, "${packageName}.fileprovider", cacheFile)
        } catch (e: Exception) {
            null
        }
    }

    private fun openGoogleLens(imageUri: Uri) {
        val clip = ClipData.newRawUri("", imageUri)

        // Method 1: Direct Google Lens entry point
        val lensIntent = Intent(Intent.ACTION_SEND).apply {
            setClassName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.lens.LensShareEntryPointActivity"
            )
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(lensIntent); return } catch (e: Exception) { }

        // Method 2: Google app generic
        val googleIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = clip
            setPackage("com.google.android.googlequicksearchbox")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(googleIntent); return } catch (e: Exception) { }

        // Method 3: Show chooser as last resort
        val chooser = Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Open with Google Lens"
        )
        try { startActivity(chooser) } catch (e: Exception) { finish() }
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

        // JS fallback: find any image whose src contains "-15/" (Instagram post image marker)
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
