package com.shanekoh.fitchecker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

data class Retailer(val name: String, val url: String)

data class Product(
    val name: String,
    val brand: String,
    val price: String,
    val url: String,
    val imageUrl: String,
    val score: Double
)

data class ResultGroup(val itemName: String, val products: List<Product>)

data class OutfitAnalysis(
    val description: String,
    val searchQuery: String,
    val colors: List<String>,
    val style: String,
    val occasion: String,
    val silhouette: String,
    val items: List<String>,
    val texture: String,
    val material: String,
    val fit: String,
    val details: String,
    val neckline: String,
    val trimming: String
)

class ResultsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_results)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: run { finish(); return }
        val imageFile = File(imagePath)
        val retailers = loadRetailers()

        Thread { runPipeline(imageFile, retailers) }.start()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ── Main pipeline ─────────────────────────────────────────────────────────
    //
    // 1. Claude analysis + Lykdat search run in PARALLEL
    // 2. Claude result triggers Phase 1 (outfit card) immediately
    // 3. After BOTH finish → Claude re-ranks Lykdat results by color + style
    // 4. Re-ranked results trigger Phase 2 (product columns)

    private fun runPipeline(imageFile: File, retailers: List<Retailer>) {
        val claudeRef = AtomicReference<OutfitAnalysis?>()
        val lykdatRef = AtomicReference<List<ResultGroup>?>()
        val latch = CountDownLatch(2)

        // Thread A: Claude outfit analysis — triggers Phase 1 as soon as done
        Thread {
            try {
                val result = callClaude(imageFile)
                claudeRef.set(result)
                runOnUiThread { showPhase1(imageFile, result.description, result.searchQuery) }
            } catch (e: Exception) {
                runOnUiThread { showPhase1(imageFile, "", "") }
            }
            latch.countDown()
        }.start()

        // Thread B: Lykdat visual search — runs fully in parallel
        Thread {
            try { lykdatRef.set(callLykdat(imageFile)) } catch (e: Exception) { }
            latch.countDown()
        }.start()

        // Wait for both, then re-rank and show Phase 2
        latch.await()

        val analysis = claudeRef.get()
        val rawGroups = lykdatRef.get() ?: emptyList()

        if (rawGroups.isEmpty()) {
            runOnUiThread {
                findViewById<View>(R.id.columnsLoading).visibility = View.GONE
                Toast.makeText(this, "No product matches found", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Re-rank: Claude scores each product 0-10 against outfit colors, style, occasion, silhouette
        val reranked = try {
            callClaudeRerank(imageFile, analysis, rawGroups)
        } catch (e: Exception) {
            // Fallback: basic color filter if re-ranking fails
            applyColorFilter(rawGroups, analysis?.colors ?: emptyList())
        }

        runOnUiThread { showPhase2(reranked, retailers) }
    }

    // ── Phase 1: Show outfit image + Claude description ───────────────────────

    private fun showPhase1(imageFile: File, description: String, query: String) {
        findViewById<View>(R.id.loadingView).visibility = View.GONE
        findViewById<View>(R.id.outfitSection).visibility = View.VISIBLE

        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap != null) findViewById<ImageView>(R.id.ivOutfitImage).setImageBitmap(bitmap)
        findViewById<TextView>(R.id.tvDescription).text = description
        findViewById<TextView>(R.id.tvQuery).text = query
    }

    // ── Phase 2: Populate product columns ────────────────────────────────────

    private fun showPhase2(groups: List<ResultGroup>, retailers: List<Retailer>) {
        findViewById<View>(R.id.columnsLoading).visibility = View.GONE

        if (groups.isEmpty()) return

        val columnsSection = findViewById<LinearLayout>(R.id.columnsSection)
        columnsSection.visibility = View.VISIBLE

        val cols = listOf(
            findViewById<LinearLayout>(R.id.col0),
            findViewById<LinearLayout>(R.id.col1),
            findViewById<LinearLayout>(R.id.col2)
        )
        val headers = listOf(
            findViewById<TextView>(R.id.colHeader0),
            findViewById<TextView>(R.id.colHeader1),
            findViewById<TextView>(R.id.colHeader2)
        )
        val dividers = listOf(
            findViewById<View>(R.id.divider01),
            findViewById<View>(R.id.divider12)
        )

        val retailerNames = retailers.map { it.name.lowercase() }.toSet()

        groups.take(3).forEachIndexed { i, group ->
            cols[i].visibility = View.VISIBLE
            headers[i].text = group.itemName.uppercase()
            if (i > 0) dividers[i - 1].visibility = View.VISIBLE

            // Dedup by brand, prioritise retailer-config brands, show top 4
            val deduped = group.products
                .groupBy { it.brand.lowercase().ifEmpty { it.url } }
                .values.map { it.maxByOrNull { p -> p.score }!! }
            val (priority, rest) = deduped.partition { p ->
                p.brand.lowercase() in retailerNames ||
                retailerNames.any { r -> p.brand.lowercase().contains(r) || r.contains(p.brand.lowercase()) }
            }
            val top4 = (priority.sortedByDescending { it.score } +
                        rest.sortedByDescending { it.score }).take(4)

            top4.forEach { addProductCard(cols[i], it) }
        }
    }

    private fun addProductCard(column: LinearLayout, product: Product) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_mini_product, column, false)
        val thumbnail = card.findViewById<ImageView>(R.id.ivMiniProduct)
        card.findViewById<TextView>(R.id.tvMiniBrand).text = product.brand.uppercase()
        card.findViewById<TextView>(R.id.tvMiniName).text = product.name
        card.findViewById<TextView>(R.id.tvMiniPrice).text = product.price
        card.setOnClickListener {
            if (product.url.isNotEmpty())
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(product.url)))
        }
        column.addView(card)

        if (product.imageUrl.isNotEmpty()) {
            Thread {
                val bmp = loadBitmap(product.imageUrl)
                if (bmp != null) thumbnail.post { thumbnail.setImageBitmap(bmp) }
            }.start()
        }
    }

    // ── API calls ─────────────────────────────────────────────────────────────

    private fun callClaude(imageFile: File): OutfitAnalysis {
        val base64Image = android.util.Base64.encodeToString(imageFile.readBytes(), android.util.Base64.NO_WRAP)
        val prompt = """
            Analyze the outfit in this image and return ONLY valid JSON with no markdown:
            {
              "description": "1-2 sentence description of the outfit",
              "search_query": "concise search terms to find similar items",
              "colors": ["every dominant color as simple words e.g. white, black, navy, beige, cream, ivory, camel, rust, olive, emerald, burgundy, coral, tan, brown, grey, pink, red, green, blue, yellow, orange, purple"],
              "style": "one of: casual, smart-casual, formal, streetwear, athleisure, boho, minimalist, preppy, edgy",
              "occasion": "one of: everyday, work, evening, date, sport, beach, festival",
              "silhouette": "brief description e.g. oversized top with slim trousers, flowy midi dress, fitted co-ord",
              "items": ["list each detected clothing or accessory item e.g. white linen shirt, navy chinos, white sneakers"],
              "texture": "visible surface texture e.g. smooth, ribbed, waffle-knit, quilted, distressed, glossy, matte",
              "material": "inferred fabric e.g. linen, cotton, denim, silk, leather, knit, chiffon, wool, synthetic",
              "fit": "how the clothing sits on the body e.g. slim, regular, oversized, baggy, cropped, relaxed, tailored",
              "details": "notable design details e.g. cargo pockets, patch pockets, pleats, ruffles, buttons, zips, drawstring",
              "neckline": "neckline style e.g. crew neck, V-neck, scoop neck, turtleneck, collared, off-shoulder, square neck",
              "trimming": "any trim or embellishment e.g. contrast stitching, piping, lace trim, embroidery, logo patch, none"
            }
        """.trimIndent()

        val responseText = postToAnthropic(base64Image, prompt, maxTokens = 768)
        val rawText = JSONObject(responseText)
            .getJSONArray("content").getJSONObject(0).getString("text")
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val parsed = JSONObject(rawText)
        val colorsArray = parsed.optJSONArray("colors") ?: JSONArray()
        val colors = (0 until colorsArray.length()).map { colorsArray.getString(it).lowercase() }
        val itemsArray = parsed.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemsArray.length()).map { itemsArray.getString(it) }

        return OutfitAnalysis(
            description = parsed.optString("description", ""),
            searchQuery = parsed.optString("search_query", ""),
            colors = colors,
            style = parsed.optString("style", ""),
            occasion = parsed.optString("occasion", ""),
            silhouette = parsed.optString("silhouette", ""),
            items = items,
            texture = parsed.optString("texture", ""),
            material = parsed.optString("material", ""),
            fit = parsed.optString("fit", ""),
            details = parsed.optString("details", ""),
            neckline = parsed.optString("neckline", ""),
            trimming = parsed.optString("trimming", "")
        )
    }

    // Re-ranking pass: Claude scores each product 0-10 against the full outfit context,
    // then results are sorted by score descending. Products scoring < 4 are dropped.
    private fun callClaudeRerank(
        imageFile: File,
        analysis: OutfitAnalysis?,
        groups: List<ResultGroup>
    ): List<ResultGroup> {
        val base64Image = android.util.Base64.encodeToString(imageFile.readBytes(), android.util.Base64.NO_WRAP)

        // Build compact product listing (max 15 per group to stay within token budget)
        val groupsText = groups.mapIndexed { gi, group ->
            "Group $gi — ${group.itemName.uppercase()}:\n" +
            group.products.take(15).mapIndexed { pi, p ->
                "$pi. ${p.brand.ifEmpty { "Unknown" }} | ${p.name} | ${p.price}"
            }.joinToString("\n")
        }.joinToString("\n\n")

        val contextStr = buildString {
            analysis?.let { a ->
                if (a.colors.isNotEmpty()) appendLine("Colors: ${a.colors.joinToString(", ")}")
                if (a.style.isNotEmpty()) appendLine("Style: ${a.style}")
                if (a.occasion.isNotEmpty()) appendLine("Occasion: ${a.occasion}")
                if (a.silhouette.isNotEmpty()) appendLine("Silhouette: ${a.silhouette}")
                if (a.fit.isNotEmpty()) appendLine("Fit: ${a.fit}")
                if (a.material.isNotEmpty()) appendLine("Material: ${a.material}")
                if (a.texture.isNotEmpty()) appendLine("Texture: ${a.texture}")
                if (a.neckline.isNotEmpty()) appendLine("Neckline: ${a.neckline}")
                if (a.details.isNotEmpty()) appendLine("Details: ${a.details}")
                if (a.trimming.isNotEmpty()) appendLine("Trimming: ${a.trimming}")
                if (a.items.isNotEmpty()) appendLine("Detected items: ${a.items.joinToString(", ")}")
            }
        }.trim().ifEmpty { "No outfit context available" }

        val prompt = """
            The outfit in the image has these characteristics:
            $contextStr

            Score each product 0-10 on how well it matches this outfit:
            - Color match is most important (wrong color = score 0-2)
            - Also consider style, occasion, and silhouette fit
            - 10 = perfect match, 5 = acceptable, 0-3 = wrong color or style

            Return ONLY valid JSON (no markdown, no explanation):
            {"groups": [{"products": [{"index": 0, "score": 8}, {"index": 1, "score": 2}]}, ...]}

            $groupsText
        """.trimIndent()

        val responseText = postToAnthropic(base64Image, prompt, maxTokens = 512)
        val rawText = JSONObject(responseText)
            .getJSONArray("content").getJSONObject(0).getString("text")
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val parsedGroups = JSONObject(rawText).optJSONArray("groups") ?: return groups

        return groups.mapIndexed { gi, group ->
            if (gi >= parsedGroups.length()) return@mapIndexed group

            val productsArray = parsedGroups.getJSONObject(gi).optJSONArray("products") ?: JSONArray()
            val scoreMap = (0 until productsArray.length()).associate { k ->
                val obj = productsArray.getJSONObject(k)
                obj.getInt("index") to obj.getDouble("score")
            }

            // Apply scores, drop anything below 4, sort by score desc
            val scored = group.products.take(15).mapIndexedNotNull { pi, p ->
                val score = scoreMap[pi] ?: return@mapIndexedNotNull null
                if (score < 4.0) null else p.copy(score = score)
            }.sortedByDescending { it.score }

            // Fallback to top 3 by Lykdat score if nothing passed threshold
            ResultGroup(group.itemName, scored.ifEmpty { group.products.sortedByDescending { it.score }.take(3) })
        }
    }

    // Shared Anthropic API call (image + text message)
    private fun postToAnthropic(base64Image: String, prompt: String, maxTokens: Int): String {
        val requestBody = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", maxTokens)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", base64Image)
                        })
                    })
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                })
            }))
        }

        val connection = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", BuildConfig.ANTHROPIC_API_KEY)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.outputStream.use { it.write(requestBody.toString().toByteArray()) }
        val text = connection.inputStream.bufferedReader().readText()
        connection.disconnect()
        return text
    }

    private fun callLykdat(imageFile: File): List<ResultGroup> {
        val boundary = "----FitCheckerBoundary${System.currentTimeMillis()}"
        val baos = ByteArrayOutputStream()
        baos.write("--$boundary\r\nContent-Disposition: form-data; name=\"api_key\"\r\n\r\n${BuildConfig.LYKDAT_API_KEY}\r\n".toByteArray())
        baos.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"outfit.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
        baos.write(imageFile.readBytes())
        baos.write("\r\n--$boundary--\r\n".toByteArray())
        val body = baos.toByteArray()

        val connection = URL("https://cloudapi.lykdat.com/v1/global/search").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.setRequestProperty("Content-Length", body.size.toString())
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 30000
        connection.outputStream.use { it.write(body) }

        val responseCode = connection.responseCode
        val responseText = if (responseCode == 200)
            connection.inputStream.bufferedReader().readText()
        else connection.errorStream?.bufferedReader()?.readText() ?: ""
        connection.disconnect()

        if (responseCode != 200) return emptyList()

        val resultGroupsJson = JSONObject(responseText)
            .getJSONObject("data").getJSONArray("result_groups")

        return (0 until minOf(3, resultGroupsJson.length())).map { i ->
            val g = resultGroupsJson.getJSONObject(i)
            val itemName = g.getJSONObject("detected_item").optString("name", "Item")
            val similarJson = g.getJSONArray("similar_products")
            val products = (0 until similarJson.length()).map { j ->
                val p = similarJson.getJSONObject(j)
                Product(
                    name = p.optString("name", ""),
                    brand = p.optString("brand_name", ""),
                    price = p.optString("price", ""),
                    url = p.optString("url", ""),
                    imageUrl = p.optJSONArray("images")?.optString(0) ?: "",
                    score = p.optDouble("score", 0.0)
                )
            }
            ResultGroup(itemName, products)
        }
    }

    // Fallback color text filter (used if re-ranking API call fails)
    private fun applyColorFilter(groups: List<ResultGroup>, colors: List<String>): List<ResultGroup> {
        if (colors.isEmpty()) return groups
        return groups.map { group ->
            val filtered = group.products.filter { p ->
                colors.any { c -> p.name.lowercase().contains(c) }
            }
            ResultGroup(group.itemName, filtered.ifEmpty { group.products })
        }
    }

    private fun loadRetailers(): List<Retailer> {
        return try {
            val json = resources.openRawResource(R.raw.retailers).bufferedReader().readText()
            val array = JSONArray(json)
            (0 until array.length()).map {
                val obj = array.getJSONObject(it)
                Retailer(obj.getString("name"), obj.getString("url"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun loadBitmap(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        return try {
            connection.connect()
            if (connection.responseCode == 200) BitmapFactory.decodeStream(connection.inputStream)
            else null
        } finally { connection.disconnect() }
    }
}
