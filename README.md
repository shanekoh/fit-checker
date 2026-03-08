# Fit Checker

An Android app that analyses any outfit image and finds matching products to buy — powered by Anthropic Claude vision and Lykdat fashion search.

Share an Instagram post (or any saved image) to Fit Checker and it will:
1. Identify the outfit's style, colors, and components
2. Search across 200+ fashion retailers for the closest product matches
3. Display results grouped by clothing item (e.g. Top, Skirt, Shoes) with images and prices

```
Instagram post → Share → Fit Checker → AI analysis → Product matches
```

## How It Works

### Pipeline

```
[Image] ──┬── Claude (vision analysis) ────────── Phase 1: Outfit card
           │
           └── Lykdat (visual product search) ──┐
                                                 ├── Claude re-ranking ── Phase 2: Product columns
                                                 └── (color + style filter)
```

**Phase 1** (appears as soon as Claude responds):
- Outfit image with AI-generated description
- Dominant colors and style summary

**Phase 2** (appears after Lykdat search + re-ranking):
- Up to 3 product columns, one per detected clothing item
- Each column shows top 4 matching products with thumbnail, brand, name, and price
- Clicking any product opens the retailer page in your browser

### Anthropic Claude (Vision API)

Two Claude calls are made per search:

**Call 1 — Outfit Analysis**
- Model: `claude-haiku-4-5-20251001`
- Input: base64-encoded outfit image
- Prompt: extracts description, search query, and dominant colors
- Output (JSON):
  ```json
  {
    "description": "1-2 sentence outfit description",
    "search_query": "concise search terms",
    "colors": ["white", "navy", "tan"]
  }
  ```
- Max tokens: 512

**Call 2 — Product Re-ranking**
- Model: `claude-haiku-4-5-20251001`
- Input: same outfit image + compact Lykdat product listing (up to 15 products per group)
- Prompt: given the outfit's colors and style, select only the product indices that match
- Output (JSON):
  ```json
  {
    "groups": [
      { "keep": [0, 2] },
      { "keep": [1] },
      { "keep": [] }
    ]
  }
  ```
- Max tokens: 256
- Fallback: if re-ranking fails, applies basic color text filter on product names

### Lykdat Global Fashion Search API

**Endpoint:** `POST https://cloudapi.lykdat.com/v1/global/search`

**Request:** multipart/form-data
- `api_key` — your Lykdat API key
- `image` — the outfit image file (JPEG)

**Response structure used:**
```json
{
  "data": {
    "result_groups": [
      {
        "detected_item": { "name": "Top" },
        "similar_products": [
          {
            "name": "...",
            "brand_name": "...",
            "price": "...",
            "url": "...",
            "images": ["https://..."],
            "score": 0.92
          }
        ]
      }
    ]
  }
}
```

- Up to 3 result groups are used (one per detected clothing item)
- Up to 15 products per group are passed to Claude for re-ranking
- Products are de-duplicated by brand, then sorted by score
- Brands matching the configured retailer list are surfaced first

### Retailer Priority

A curated list of 200+ fashion retailers is stored in `app/src/main/res/raw/retailers.json`. Products whose brand names match this list are shown before others within each column.

## Features

- Share an Instagram post URL directly from the Instagram share button
- Or share a saved image from your gallery
- Loading is split into two phases to show outfit analysis before product results
- Product thumbnails load asynchronously per card
- Tapping a product opens the retailer page in the browser
- No login required

## Setup

### Requirements
- Android 7.0+ (API 24)
- Anthropic API key (Claude Haiku)
- Lykdat API key (Global Search)

### Installation

1. Clone the repo
   ```bash
   git clone https://github.com/shanekoh/fit-checker.git
   ```
2. Open in Android Studio

3. Add your API keys to `local.properties` (create if it doesn't exist):
   ```
   ANTHROPIC_API_KEY=sk-ant-...
   LYKDAT_API_KEY=your_lykdat_key
   ```

4. Run on your device or build an APK via **Build → Build APK**

> `local.properties` is git-ignored and never committed.

## Usage

**From Instagram (URL share):**
1. Open any Instagram post
2. Tap the share icon → More → **Fit Checker**
3. Fit Checker extracts the image silently and begins analysis

**From your gallery:**
1. Open gallery → share any photo → **Fit Checker**
2. Analysis begins immediately

## How Instagram Image Extraction Works

Instagram's CDN uses `-15/` in URL paths for post images. Fit Checker loads the Instagram page in an invisible WebView, intercepts outgoing image requests at the network layer, and captures the highest-resolution post image (preferring `s1080x`, `s750x`, or `s640x` variants). The user never sees the WebView — they see a loading screen from the moment they share.

## Tech Stack

- Kotlin
- Anthropic Messages API (Claude Haiku, vision)
- Lykdat Global Fashion Search API
- Android WebView (invisible, for Instagram image extraction)
- `HttpURLConnection` — no third-party HTTP libraries
- `org.json` — no third-party JSON libraries
- AndroidX NestedScrollView + programmatic column layout

## Roadmap

- [ ] Support carousel posts (multiple images)
- [ ] Cache results for recently analysed outfits
- [ ] Filter results by price range
- [ ] iOS version
