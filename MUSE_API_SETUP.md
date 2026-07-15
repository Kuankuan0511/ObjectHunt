# Pigeon Hunter 🐦 - Muse API Integration

An Android app that uses Meta's Muse Spark AI (via Model API) to detect pigeons in photos and identify their specific type/species.

## Features

- **Camera Integration**: Take photos using the device camera
- **AI-Powered Detection**: Uses Muse Spark via Model API to analyze images
- **Pigeon Type Identification**: Identifies specific pigeon species (Feral, Wood Pigeon, Racing Homer, etc.)
- **Detailed Analysis**: Provides features, location, and confidence level
- **Material Design 3**: Modern UI built with Jetpack Compose

## What Was Implemented

### 1. MuseApiClient.kt
A complete HTTP client for Meta's Model API that:
- Converts Bitmap images to Base64 for API transmission
- Builds properly formatted JSON requests with multimodal input (text + image)
- Parses structured responses to extract pigeon details
- Handles errors gracefully

**Key API Details:**
- **Endpoint**: `https://api.ai.meta.com/v1/responses`
- **Model**: `muse-spark-1.1-eval` (free tier) or `muse-spark-20260519` (BYOC)
- **Input**: Multimodal (text prompt + base64-encoded image)
- **Output**: Structured text with pigeon type, features, location, and confidence

### 2. MainActivity.kt Updates
- Integrated `MuseApiClient` for pigeon detection
- Replaced ML Kit with Muse API calls
- Added coroutine support for async API calls
- State management for pigeon detection results

### 3. UI Updates
- New "Pigeon Hunter" branding
- Displays pigeon type (e.g., "Feral Pigeon", "Wood Pigeon")
- Shows confidence percentage
- Lists distinguishing features (color, markings, size)
- Indicates location in the image
- Color-coded results (green for found, gray for not found)

## Setup Instructions

### Step 1: Get Your Model API Key

1. Go to **Model API Internal Developer Center**:
   https://modelapi.internalmeta.com/

2. **Create a Team** (if you haven't already):
   - Click "Create Team" (top right)
   - Give your team a name (e.g., "Pigeon Hunter")
   - Assign an oncall owner

3. **Get Your API Key**:
   - Navigate to your Team
   - Go to the "Keys" tab
   - Copy the API key (starts with `mg-api-...`)

### Step 2: Choose Your Model

**Option A: Free Evaluation Tier (Recommended for testing)**
- Model: `muse-spark-1.1-eval`
- No BYOC capacity required
- Limited rate limits
- Good for development and testing

**Option B: Production Tier (Requires BYOC)**
- Model: `muse-spark-20260519`
- Requires GPU capacity transfer via BYOC process
- Higher rate limits
- See [BYOC Onboarding Guide](https://docs.google.com/document/d/1IoTMS_vDDwb0_tLRaA2EtmToTVZ8YliL9Pr6XI1Kbho/edit)

### Step 3: Configure the App

1. Open `app/src/main/java/com/aai/steel/objecthunt/MainActivity.kt`

2. Find these lines (around line 20-25):
   ```kotlin
   private val MODEL_API_KEY = "YOUR_MODEL_API_KEY_HERE"
   private val MODEL_NAME = "muse-spark-1.1-eval"
   ```

3. Replace with your actual API key:
   ```kotlin
   private val MODEL_API_KEY = "mg-api-8c276b149adf..." // Your actual key
   private val MODEL_NAME = "muse-spark-1.1-eval" // or "muse-spark-20260519"
   ```

### Step 4: Sync Gradle

1. Open the project in Android Studio
2. When prompted, click **"Sync Now"**
3. Wait for Gradle sync to complete

### Step 5: Build and Run

1. Connect an Android device (API 24+) or start an emulator
2. Click **Run** in Android Studio
3. Grant camera permission when prompted
4. Tap "📸 Take Photo" to capture an image
5. Wait for Muse Spark to analyze (10-30 seconds)
6. View the pigeon detection results!

## How It Works

### Request Flow
```
1. User takes photo
   ↓
2. Bitmap converted to Base64 JPEG
   ↓
3. JSON request built with:
   - Model: muse-spark-1.1-eval
   - Input: Text prompt + base64 image
   - Parameters: temperature=1.0, top_p=1.0
   ↓
4. HTTP POST to https://api.ai.meta.com/v1/responses
   ↓
5. Muse Spark analyzes image
   ↓
6. Response parsed for pigeon details
   ↓
7. UI updated with results
```

### Example Request
```json
{
  "model": "muse-spark-1.1-eval",
  "input": {
    "items": [{
      "type": "message",
      "role": "user",
      "content": [
        {
          "type": "input_text",
          "text": "Analyze this image for pigeons. Provide: HAS_PIGEON: YES/NO, TYPE: [species], FEATURES: [description], LOCATION: [where], CONFIDENCE: [High/Medium/Low]"
        },
        {
          "type": "input_image",
          "image_url": "data:image/jpeg;base64,/9j/4AAQSkZJRg..."
        }
      ]
    }]
  },
  "temperature": 1.0,
  "top_p": 1.0,
  "stream": false
}
```

### Example Response
```json
{
  "output": [{
    "type": "message",
    "content": [{
      "type": "output_text",
      "text": "HAS_PIGEON: YES\nTYPE: Feral Pigeon\nFEATURES: Gray-blue body with iridescent green and purple neck feathers. Dark wing bars.\nLOCATION: Perched on a concrete ledge in the center-right.\nCONFIDENCE: High"
    }]
  }]
}
```

## Pigeon Types Detected

Muse Spark can identify:
- **Rock Pigeon** (Columba livia) - Wild ancestor
- **Feral Pigeon** - Common city pigeons
- **Wood Pigeon** (Columba palumbus) - Larger, pink breast, white neck patches
- **Stock Dove** - Smaller, darker, no white patches
- **Racing Homer** - Athletic build, often has leg bands
- **Fantail Pigeon** - Distinctive fan-shaped tail
- **King Pigeon** - Large, bred for meat
- **Unknown** - When type cannot be determined

## Project Structure

```
app/src/main/java/com/aai/steel/objecthunt/
├── MainActivity.kt          # Main UI and camera integration
├── MuseApiClient.kt         # Model API client for pigeon detection
└── ui/theme/                # Material Design theme
```

## Dependencies Added

**gradle/libs.versions.toml:**
- `okhttp = "4.12.0"` - HTTP client for API calls
- `kotlinx-coroutines = "1.7.3"` - Async operations
- `lifecycle-viewmodel = "2.8.0"` - Lifecycle-aware components

**AndroidManifest.xml:**
- `android.permission.INTERNET` - Required for API calls
- `android.permission.CAMERA` - Required for taking photos

## Troubleshooting

### "API error: 401" or "Invalid API key"
- Verify your API key is correct in MainActivity.kt
- Ensure you copied the full key from the Developer Center
- Check that your key hasn't expired

### "API error: 403" or "Model not accessible"
- For `muse-spark-20260519`: You need BYOC capacity
- Switch to `muse-spark-1.1-eval` for free tier access
- Or complete BYOC onboarding to get production access

### "API error: 429" (Rate limit)
- You've exceeded the rate limit for your tier
- Wait a few minutes and try again
- For higher limits, transfer more BYOC capacity

### Slow response times (30+ seconds)
- Normal for first request (model cold start)
- Subsequent requests should be faster (10-20 seconds)
- Large images take longer - the app compresses to 80% JPEG quality

### App crashes on API call
- Check Logcat for error messages
- Ensure INTERNET permission is in AndroidManifest.xml
- Verify device has internet connectivity

## Security Notes

⚠️ **For Production Use:**
- **NEVER** hardcode API keys in mobile apps
- API keys can be extracted from APKs
- Instead, create a backend service:
  1. Mobile app sends image to your backend
  2. Backend holds the API key securely
  3. Backend calls Model API
  4. Backend returns results to mobile app

## Cost Considerations

- **Evaluation tier** (`muse-spark-1.1-eval`): Free, limited rate
- **BYOC tier** (`muse-spark-20260519`): 
  - You provide GPU capacity
  - Cost depends on your capacity contribution
  - See [BYOC Capacity Calculator](https://www.internalfb.com/wiki/Model_API/Internal_Customers/Capacity)

## Next Steps

1. **Test with different pigeons**: Try various pigeon types and lighting conditions
2. **Improve prompts**: Experiment with different prompt formulations
3. **Add caching**: Cache results to avoid re-analyzing the same image
4. **Backend integration**: Move API key to a secure backend service
5. **Batch processing**: Analyze multiple images in sequence

## Resources

- **Model API Docs**: https://www.internalfb.com/wiki/Model_API/Internal_Customers/
- **Onboarding Guide**: https://www.internalfb.com/wiki/Model_API/Internal_Customers/Onboarding/
- **BYOC Guide**: https://docs.google.com/document/d/1IoTMS_vDDwb0_tLRaA2EtmToTVZ8YliL9Pr6XI1Kbho/edit
- **Workplace Support**: https://fb.workplace.com/groups/1325927276251774 (Muse Spark Internal Access)
- **API Playground**: https://modelapi.internalmeta.com/ (test prompts without code)

## License

This is a demo project for internal Meta use. Follow your team's guidelines for API key management and data handling.
