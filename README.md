# ObjectHunt - Android Object Recognition Demo

An Android demo app that uses the device camera to take photos and identifies objects using Google ML Kit's on-device image labeling.

## Features

- **Camera Integration**: Uses Android's built-in camera via ActivityResult API
- **Object Recognition**: Uses ML Kit Image Labeling to identify objects in photos
- **Confidence Scores**: Displays detection confidence percentages for each identified object
- **Material Design 3**: Modern UI built with Jetpack Compose

## How It Works

1. **Camera Permission**: App requests camera permission on first launch
2. **Take Photo**: Tap the "Take Photo" button to launch the camera
3. **Analysis**: ML Kit analyzes the image on-device (no internet required)
4. **Results**: Displays a list of detected objects with confidence scores
5. **Retry**: Tap "Take Another Photo" to capture a new image

## Technical Details

### Dependencies Added

**gradle/libs.versions.toml:**
- `com.google.mlkit:image-labeling` (v17.0.9)

**AndroidManifest.xml:**
- `CAMERA` permission
- Camera hardware feature requirement

### Key Components

**MainActivity.kt:**
- `ObjectHuntScreen`: Main composable UI with two states (camera prompt and results)
- `takePictureLauncher`: Uses `ActivityResultContracts.TakePicturePreview()` for simple camera capture
- `analyzeImageWithMLKit()`: Processes image with ML Kit and returns labels
- `LabelItem`: Displays individual detection results with confidence percentage

### ML Kit Image Labeling

The app uses ML Kit's on-device image labeler which can recognize 400+ common objects including:
- Food items (apple, pizza, coffee, etc.)
- Animals (cat, dog, bird, etc.)
- Plants and flowers
- Places and landmarks
- Activities and sports
- Household objects

No API key or internet connection required - all processing happens on-device.

## Building and Running

1. Open the project in Android Studio
2. Sync Gradle files (dependencies will be downloaded automatically)
   - Click **"Sync Now"** when prompted in Android Studio
3. Connect an Android device or start an emulator (API 24+)
4. Run the app
5. Grant camera permission when prompted
6. Tap "Take Photo", point the camera at an object, and capture
7. View the detected objects with confidence scores

## Requirements

- Android Studio Hedgehog or later
- Android device/emulator with API 24 (Android 7.0) or higher
- Camera hardware (for physical devices)

## Notes

- The first time you run the app, ML Kit will download the image labeling model (approx. 10-20MB)
- Processing happens on-device for privacy - no images are sent to the cloud
- For better accuracy, ensure good lighting and clear focus on the object
- The simplified version uses the system camera app via Intent, which is more reliable than a custom camera implementation
