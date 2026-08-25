[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="80" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/acab.naiveha.upnpkino)

# UPnP Kino by naiveHA
**Uncomplicatedly simple**: stream your media library (video and audio) directly from your Android device to any UPnP/DLNA compatible player.

## Features
- **Wide Format Support**: Stream video (`mp4`, `mkv`, `mov`, `webm`) and audio (`mp3`, `m4a`, `aac`, `flac`, `wav`, `opus`).
- **Large File Support**: Unlike other legacy apps, UPnP Kino handles files larger than 2GB (perfect for high-quality H264 encodes).
- **Background Streaming**: Keeps the media server running even when the screen is off or you're using other apps.
- **Modern Backend**: Built on Eclipse Jetty 12 for reliable performance.
- **Privacy Focused**: No tracking, no unnecessary permissions.

## Compatibility
- **Device Requirements**: Android 8.0 (API 26) or higher.
- **Tested Players**: 
  - **VLC**: Extensively tested on Desktop and Mobile.
  - **eezUPnP**: Confirmed working.
  - **Kodi**: Confirmed working. In certain cases, you might need to start Kodi *first*, then start UPnP Kino.

## Permissions Explained
- **Local Network**: Required to find and connect to players on your WiFi.
- **Media Access**: Needed to read the files you choose to share. On Android 14+, you can grant access to specific files only.
- **Notifications**: Used to show server status and provide quick-stop controls.
- **Wake Lock**: Prevents the stream from dropping if the phone enters deep sleep.

## ☕ Support the Project
If you find this app useful, consider supporting development!
**BTC Address**: `1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh`