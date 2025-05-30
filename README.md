# Geotag Video Camera

![Geotag Camera]()

An Android application that provides a camera with real-time geolocation overlay, including map display, coordinates, and address information.

## Features

* **Ad-free experience** - No advertisements, completely free and open-source
* **Live geolocation overlay** - See your current location on a map while taking photos or videos
* **Multiple map providers** - Support for Mapbox and Geoapify APIs
* **Customizable display** - Configure what information appears on screen
* **Screen recording integration** - Capture videos with the overlay using your device's screen recorder
* **Photo geotagging** - Embed location metadata in captured photos
* **Address display** - See the current address based on your location
* **Speed indicator** - Display current movement speed
* **Adjustable map zoom** - Customize the map zoom level to your preference
* **GPS status indicator** - See the status and accuracy of your GPS connection
* **Clean, fast and lightweight** interface

## Installation

### Option 1: Install from F-Droid or IzzyOnDroid

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/org.app.geotagvideocamera)
[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" height="80">](https://apt.izzysoft.de/packages/org.app.geotagvideocamera)

### Option 2: Download APK

Download the latest APK from the [Releases Section](https://github.com/mlm-games/geotag-video-camera/releases/latest).

## How to Use

1. **First Launch**: Upon first launch, you'll be prompted to enter a map API key. You can get a free key from [Mapbox](https://www.mapbox.com/) or [Geoapify](https://www.geoapify.com/).

2. **Taking Photos**: 
   - Tap the camera button to take a photo with the current geolocation data embedded as metadata
   - The photo will be saved to your device's Pictures/GeotagCamera folder

3. **Recording Videos**:
   - Use your device's screen recorder to capture the camera feed with the overlay
   - The app displays the overlay information but doesn't record video directly
   - Double tap the screen to access settings

4. **Customizing Settings**:
   - Double tap anywhere on the screen to open the settings menu
   - Adjust map zoom level
   - Toggle display elements (coordinates, map, address, etc.)
   - Configure map provider API keys

5. **Debug Mode**:
   - For testing purposes, you can enable a debug location (Golden Gate Bridge)

## Permissions

The app requires the following permissions:
- Camera access
- Microphone access (for video)
- Location access (fine and coarse)
- Internet access (for map tiles)
- Storage access (to save media)

## Requirements

- Android 7.0 (API level 24) or higher
- Internet connection for map display
- GPS/Location services enabled

## Development

This application is built using:
- Jetpack Compose for the UI
- CameraX for camera functionality
- Android's location services
- Kotlin coroutines for asynchronous operations

### Building from Source

1. Clone the repository
2. Open the project in Android Studio
3. Build using Gradle: `./gradlew assembleDebug`

## License

[GNU GPLv3](https://www.gnu.org/licenses/gpl-3.0.en.html)

## Contributing

Contributions are welcome! Feel free to submit issues or pull requests.

---

Thank you for using Geotag Video Camera!
