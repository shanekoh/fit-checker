package com.shanekoh.fitchecker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

sealed class ListItem {
    data class OutfitCard(val description: String, val query: String, val imagePath: String) : ListItem()
    data class SectionHeader(val title: String) : ListItem()
    data class ProductCard(val product: Product) : ListItem()
    data class RetailerRow(val retailer: Retailer, val searchUrl: String) : ListItem()
}

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

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        val retailers = loadRetailers()
        fetchAndShow(imageFile, retailers, recyclerView)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

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

    private fun fetchAndShow(imageFile: File, retailers: List<Retailer>, recyclerView: RecyclerView) {
        Thread {
            val claudeResult = AtomicReference<Triple<String, String, List<String>>?>()
            val lykdatResult = AtomicReference<List<Product>?>()
            val latch = CountDownLatch(2)

            Thread {
                try { claudeResult.set(callClaude(imageFile)) } catch (e: Exception) { }
                latch.countDown()
            }.start()

            Thread {
                try { lykdatResult.set(callLykdat(imageFile)) } catch (e: Exception) { }
                latch.countDown()
            }.start()

            latch.await()

            val claude = claudeResult.get()
            val rawProducts = lykdatResult.get() ?: emptyList()

            if (claude == null && rawProducts.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Search failed. Check your connection.", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@Thread
            }

            val colors = claude?.third ?: emptyList()

            // Step 1: filter by color — keep only products whose name contains at least one outfit color
            val colorFiltered = if (colors.isEmpty()) rawProducts else {
                rawProducts.filter { p ->
                    colors.any { color -> p.name.lowercase().contains(color) }
                }.ifEmpty { rawProducts } // fallback to all if nothing matches
            }

            // Step 2: deduplicate — one product per brand (highest score)
            val dedupedByBrand = colorFiltered
                .groupBy { it.brand.lowercase().ifEmpty { it.url } }
                .values
                .map { group -> group.maxByOrNull { it.score }!! }

            // Step 3: prioritise brands from the retailers config list, then sort by score
            val retailerNames = retailers.map { it.name.lowercase() }.toSet()
            val (prioritised, others) = dedupedByBrand.partition { p ->
                p.brand.lowercase() in retailerNames ||
                retailerNames.any { r -> p.brand.lowercase().contains(r) || r.contains(p.brand.lowercase()) }
            }
            val top10 = (prioritised.sortedByDescending { it.score } +
                         others.sortedByDescending { it.score }).take(10)

            val items = mutableListOf<ListItem>()
            items.add(ListItem.OutfitCard(
                claude?.first ?: "Outfit analysis unavailable",
                claude?.second ?: "",
                imageFile.absolutePath
            ))
            if (top10.isNotEmpty()) {
                items.add(ListItem.SectionHeader("BEST MATCHES"))
                top10.forEach { items.add(ListItem.ProductCard(it)) }
            }

            runOnUiThread {
                findViewById<View>(R.id.loadingLayout).visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = ResultsAdapter(items) { url ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }.start()
    }

    private fun callClaude(imageFile: File): Triple<String, String, List<String>> {
        val base64Image = android.util.Base64.encodeToString(imageFile.readBytes(), android.util.Base64.NO_WRAP)

        val prompt = """
            Analyze the outfit in this image and return ONLY valid JSON with no markdown or extra text:
            {
              "description": "1-2 sentence description of the outfit",
              "search_query": "concise search terms to find similar items",
              "colors": ["list every dominant color in the outfit as simple color words e.g. white, black, navy, beige, red, green, brown, grey, pink, cream, ivory, camel, tan, rust, olive, cobalt, emerald, burgundy, coral"]
            }
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("max_tokens", 512)
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
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    })
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

        val responseText = connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val rawText = JSONObject(responseText)
            .getJSONArray("content").getJSONObject(0).getString("text")
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val parsed = JSONObject(rawText)
        val colorsArray = parsed.optJSONArray("colors") ?: JSONArray()
        val colors = (0 until colorsArray.length()).map { colorsArray.getString(it).lowercase() }
        return Triple(parsed.getString("description"), parsed.getString("search_query"), colors)
    }

    private fun callLykdat(imageFile: File): List<Product> {
        val boundary = "----FitCheckerBoundary${System.currentTimeMillis()}"

        // Build multipart body
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
        val responseText = if (responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else {
            connection.errorStream?.bufferedReader()?.readText() ?: ""
        }
        connection.disconnect()

        if (responseCode != 200) return emptyList()

        val products = mutableListOf<Product>()
        val resultGroups = JSONObject(responseText)
            .getJSONObject("data")
            .getJSONArray("result_groups")

        for (i in 0 until resultGroups.length()) {
            val similar = resultGroups.getJSONObject(i).getJSONArray("similar_products")
            for (j in 0 until similar.length()) {
                val p = similar.getJSONObject(j)
                val imageUrl = p.optJSONArray("images")?.optString(0) ?: ""
                products.add(Product(
                    name = p.optString("name", "Unknown"),
                    brand = p.optString("brand_name", ""),
                    price = p.optString("price", ""),
                    url = p.optString("url", ""),
                    imageUrl = imageUrl,
                    score = p.optDouble("score", 0.0)
                ))
            }
        }

        return products.sortedByDescending { it.score }.take(10)
    }

    private fun buildSearchUrl(retailer: Retailer, query: String): String {
        val base = retailer.url.trimEnd('/')
        return "$base/search?q=${Uri.encode(query)}"
    }
}

class ResultsAdapter(
    private val items: List<ListItem>,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_OUTFIT_CARD = 0
        private const val TYPE_SECTION_HEADER = 1
        private const val TYPE_PRODUCT = 2
        private const val TYPE_RETAILER = 3
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ListItem.OutfitCard -> TYPE_OUTFIT_CARD
        is ListItem.SectionHeader -> TYPE_SECTION_HEADER
        is ListItem.ProductCard -> TYPE_PRODUCT
        is ListItem.RetailerRow -> TYPE_RETAILER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OUTFIT_CARD -> OutfitCardHolder(inflater.inflate(R.layout.item_outfit_card, parent, false))
            TYPE_SECTION_HEADER -> SectionHeaderHolder(inflater.inflate(R.layout.item_section_header, parent, false))
            TYPE_PRODUCT -> ProductHolder(inflater.inflate(R.layout.item_product_card, parent, false))
            else -> RetailerHolder(inflater.inflate(R.layout.item_retailer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.OutfitCard -> (holder as OutfitCardHolder).bind(item)
            is ListItem.SectionHeader -> (holder as SectionHeaderHolder).bind(item)
            is ListItem.ProductCard -> (holder as ProductHolder).bind(item, onItemClick)
            is ListItem.RetailerRow -> (holder as RetailerHolder).bind(item, onItemClick)
        }
    }

    override fun getItemCount() = items.size

    class OutfitCardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.ivOutfitImage)
        private val description: TextView = view.findViewById(R.id.tvDescription)
        private val query: TextView = view.findViewById(R.id.tvQuery)
        fun bind(item: ListItem.OutfitCard) {
            val bitmap = BitmapFactory.decodeFile(item.imagePath)
            if (bitmap != null) image.setImageBitmap(bitmap)
            description.text = item.description
            query.text = item.query
        }
    }

    class SectionHeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tvSectionTitle)
        fun bind(item: ListItem.SectionHeader) { title.text = item.title }
    }

    class ProductHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.ivProductImage)
        private val brand: TextView = view.findViewById(R.id.tvBrand)
        private val name: TextView = view.findViewById(R.id.tvProductName)
        private val price: TextView = view.findViewById(R.id.tvPrice)
        private val score: TextView = view.findViewById(R.id.tvScore)

        fun bind(item: ListItem.ProductCard, onClick: (String) -> Unit) {
            val p = item.product
            brand.text = p.brand.uppercase()
            name.text = p.name
            price.text = p.price.ifEmpty { "" }
            score.text = "${(p.score * 100).toInt()}% match"
            thumbnail.setImageResource(android.R.color.darker_gray)

            if (p.imageUrl.isNotEmpty()) {
                Thread {
                    try {
                        val bmp = loadBitmap(p.imageUrl)
                        if (bmp != null) thumbnail.post { thumbnail.setImageBitmap(bmp) }
                    } catch (e: Exception) { /* keep placeholder */ }
                }.start()
            }

            itemView.setOnClickListener { if (p.url.isNotEmpty()) onClick(p.url) }
        }

        private fun loadBitmap(url: String): Bitmap? {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            return try {
                connection.connect()
                if (connection.responseCode == 200)
                    BitmapFactory.decodeStream(connection.inputStream)
                else null
            } finally {
                connection.disconnect()
            }
        }
    }

    class RetailerHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvRetailerName)
        fun bind(item: ListItem.RetailerRow, onClick: (String) -> Unit) {
            name.text = item.retailer.name
            itemView.setOnClickListener { onClick(item.searchUrl) }
        }
    }
}
