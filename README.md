# Geotag Video Camera

A simple CameraX + Compose app that overlays a live map and location info on top of the camera preview.

<p align="center">
  <a href="https://f-droid.org/packages/org.app.geotagvideocamera/">
    <img src="https://f-droid.org/badge/get-it-on.png" height="80" alt="Get it on F-Droid">
  </a>
  <a href="https://apt.izzysoft.de/fdroid/index/apk/org.app.geotagvideocamera">
    <img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="54" alt="Get it at IzzyOnDroid">
  </a>
    <a href="https://play.google.com/store/apps/details?id=org.app.geotagvideocamera">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="80" alt="Get it on Google Play">
  </a>

</p>

<p align="center">
  <a href="https://f-droid.org/packages/org.app.geotagvideocamera/">
    <img alt="F-Droid version"
         src="https://img.shields.io/f-droid/v/org.app.geotagvideocamera?logo=f-droid&logoColor=white&label=F-Droid&labelColor=1976d2&color=1976d2">
  </a>
  <a href="https://apt.izzysoft.de/fdroid/index/apk/org.app.geotagvideocamera">
    <img alt="IzzyOnDroid version"
         src="https://img.shields.io/f-droid/v/org.app.geotagvideocamera?baseUrl=https://apt.izzysoft.de/fdroid&label=IzzyOnDroid&labelColor=1b1f23&color=00a3d9&logo=android&logoColor=white">
  </a>
  <!-- <a href="https://shields.rbtlog.dev/org.app.geotagvideocamera">
    <img src="https://shields.rbtlog.dev/simple/org.app.geotagvideocamera?style=for-the-badge" alt="Reproducible Builds status">
  </a> -->
</p>

<p align="center">
  <!-- <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" alt="Screenshot 1" width="46%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" alt="Screenshot 2" width="46%"> -->
  <!-- <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" alt="Screenshot 3" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" alt="Screenshot 4" width="24%"> -->
</p>

> UI tip: Double‑tap anywhere on the preview to open Settings.

## Initial context

On first launch, if the app is using MapLibre demo tiles (the default fallback), you’ll see a one‑time notice:
- MapLibre demo tiles are for demonstration only since they do not show terrain.
- For better maps, use MapTiler or Geoapify and provide your API key.

To configure:
1. Double‑tap anywhere on the camera preview to open Settings.
2. Go to Settings → Map.
3. Pick a provider:
   - MapTiler: paste your API key in “MapTiler API key”.
   - Geoapify: paste your API key in “Geoapify API key”.
   - MapLibre: provide your own style URL if you self‑host tiles; otherwise the app will use demo tiles.
4. Optionally adjust the map zoom and overlay preferences.

If no key is provided for MapTiler/Geoapify or no custom style URL is set for MapLibre, the app falls back to MapLibre demo tiles.

## Highlights
- Live overlay: map + coordinates on the camera preview
- Works with MapLibre (default), MapTiler, or Geoapify
- Compose UI, clean controls; simple mode toggle
- Optional debug location for demos

## Quickstart
1) Open the app, double‑tap the preview → Settings.  
2) Settings → Map: choose a provider.  
   - MapTiler or Geoapify: paste your API key.  
   - MapLibre: use your own style URL, otherwise demo tiles are used.  
3) Frame your shot and record.

## Permissions
- Camera
- Location (for GPS overlays and reverse geocoding)
- Optional: notifications, audio (for future features)

## Notes
- Map tiles are fetched from the provider you select.
- When using demo tiles, terrain and detail may be limited.
- Location data is only embedded locally. No tracking or cloud uploads.

## Support
- Issues: https://github.com/mlm-games/mlm-games-geotag-video-camera/issues

## License
GPL‑3.0 — see [LICENSE](LICENSE).
