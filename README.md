# Geotag Video Camera

A simple Compose + CameraX app that overlays location/map info on top of a camera preview.

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

## Usage

- Open Settings: double‑tap anywhere on the preview.
- Camera mode button: can be shown/hidden in Settings → Camera (“Hide mode button”).
- Debug location: Settings → System → “Debug location” shows a demo location (Golden Gate Bridge).

## Permissions

- Camera
- Fine location (for GPS overlays and reverse geocoding)
- Optional: notifications, audio (for future features), etc.

## Supported Map providers

- MapLibre (default; demo tiles if no custom style URL)
- MapTiler (requires API key)
- Geoapify (requires API key)