# Fit Checker

An Android app that lets you reverse image search any Instagram outfit directly with Google Lens — no more saving screenshots and manually searching.

## How It Works

Share any Instagram post to **Fit Checker** and it will automatically open Google Lens with the outfit image, showing you where to buy similar items.

```
Instagram post → Share → Fit Checker → Google Lens → Shop the look
```

## Features

- Share an Instagram post URL directly from Instagram's share button
- Or share a saved image from your gallery
- Automatically extracts the post image (not profile pictures or thumbnails)
- Opens Google Lens with the image pre-loaded for instant search
- No login required, no server, works entirely on-device

## Setup

### Requirements
- Android 7.0+ (API 24)
- Google app installed (comes pre-installed on most Android phones)

### Installation

1. Clone the repo
   ```bash
   git clone https://github.com/shanekoh/fit-checker.git
   ```
2. Open in Android Studio
3. Run on your device (or build an APK via Build → Build APK)

## Usage

**From Instagram (URL share):**
1. Open any Instagram post
2. Tap the share icon → More → **Fit Checker**
3. Google Lens opens automatically with the outfit image

**From your gallery (saved image):**
1. Save any Instagram image to your gallery
2. Open gallery → Share → **Fit Checker**
3. Google Lens opens automatically

## How the Image Extraction Works

Instagram's CDN uses `-15/` in the URL path for post images and `-19/` for profile pictures. Fit Checker intercepts image requests at the network level as the post loads, filters to only `-15/` URLs, and picks the highest resolution one — making it reliable regardless of how Instagram changes its page layout.

## Tech Stack

- Kotlin
- Android WebView (for rendering Instagram posts)
- Google Lens intent
- AndroidX FileProvider (for secure file sharing between apps)

## Roadmap

- [ ] Support carousel posts (multiple images)
- [ ] iOS version (Share Extension)
- [ ] Parallel search across multiple shopping platforms
