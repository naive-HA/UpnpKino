[<img src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroidButtonGreyBorder_nofont.png" height="80" alt="Get it at IzzyOnDroid">](https://apt.izzysoft.de/packages/acab.naiveha.upnpkino)

# UPnP Kino by naiveHA
Uncomplicated simple: stream your video (mp4, mkv, avi, mov, wmv, webm) and/or audio (mp3, m4a, aac, flac, wav, opus) files stored on your Android phone to another phone, tablet, computer or TV set running a compatible video player.

UPnP Kino is compatible with VLC, eezUPnP and other upnp enabled players and control points.

Based on Eclipse Jetty 12 and requires minimum Android 15 VanillaIceCream (API 35)

# How does it work?
Press the "Movies" or "Music" library icon to select the folder/folders containing your video files and/or audio files.
[![mobileApp](https://github.com/naive-HA/UpnpKino/blob/main/fastlane/metadata/android/en_US/images/phoneScreenshots/1.png)](https://github.com/naive-HA/UpnpKino/blob/main/fastlane/metadata/android/en_US/images/phoneScreenshots/1.png)
Connected to WiFi and press "Start UPnP Kino". 

Then open your UPnP compatible video player, like VLC, and navigate to "Browse" on another WiFi connected phone or tablet, or "View/Playlist/Universal Plug'n'Play" on a desktop and enjoy your videos. The app has been extensively tested with VLC but other UPnP players should work just fine.
[![desktopVLC](https://github.com/naive-HA/UpnpKino/blob/main/desktopVLC.png)](https://github.com/naive-HA/UpnpKino/blob/main/desktopVLC.png)
[![desktopVLC](https://github.com/naive-HA/UpnpKino/blob/main/mobileVLC.png)](https://github.com/naive-HA/UpnpKino/blob/main/mobileVLC.png)

# Why not just use PlainUPnP (com.m3sv.plainupnp)?
Well, first of all, it seems that the app has been removed from F-Droid. And, anyway, the app had a bug which made it impossible to stream video files over 2,147,483,647 bytes. Which is just over 2GB. Nowadays, most H264 encoded video files are above 2GB. "UPnP Kino" solves that problem in a fit-for-purpose way. Enjoy!

# Buy me a coffee
If you like it, remember to donate some satoshi/BTC to:

1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh