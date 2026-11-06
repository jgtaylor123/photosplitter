# PhotoSplitter

Free, offline Android app for separating printed photographs from a single batch image.

## Experimental V1

1. Arrange photos with gaps on a plain dark tabletop.
2. Take a photo using the phone camera.
3. Open PhotoSplitter and choose that image.
4. Tap **Detect, crop and save**.
5. Find JPEGs under **Pictures/PhotoSplitter**.

**Important:** The initial detector uses simple brightness-based segmentation. It can miss photos, merge adjacent photos, or crop incorrectly. There is no perspective correction, manual crop editor, or in-app camera yet. **Do not discard originals until every crop has been checked and backed up.**

## Build

Open this repository in Android Studio with JDK 17 and Android SDK 35, sync Gradle, then run on a device. Use **Build > Build APK(s)** to create an APK.

The app requires no internet permission and does not upload images.

## Next

- CameraX high-resolution in-app capture
- More robust edge detection and perspective correction
- Visual crop review and manual corrections
- Batch queue
