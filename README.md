# Press Sheet

A home screen (launcher) for John's Samsung phone, built for The Ink Stop.

It works like the Samsung home screen, with a few extras:

- **Pages of icons over your wallpaper.** It starts from John's own Samsung layout: the same two pages, the same apps in about the same spots, the same folders (Nintendo, Food & rides, Banking, AI) and the same dock (Phone, Messages, InkStop Mail, Chrome, Starbucks). The grid is 8 across by 8 down, like the Samsung pages.
- **Swipe left and right** between pages. The dots above the dock show which page you're on.
- **Swipe up** for every app: search at the top, recently used apps, then A to Z with a letter scroller on the side. Search also finds InkFlow pages (New order, Orders board, Printers and more) and can search the web.
- **Swipe down** to pull down notifications.
- **Weather + InkFlow card** in place of Samsung's weather widget. Weather is from the National Weather Service, with Open-Meteo as the backup. The InkFlow line shows today's due and late orders and whether a printer is running.
- **Home button** closes whatever is open and goes back to the home page.

## Changing the layout

- **Press and hold an icon** to move it, put it in a folder, remove it from the home screen, or see app info.
  When moving, tap an empty spot to put it there, tap another app to swap places, or tap a folder to drop the app in.
- **Press and hold an empty spot** to add an app, a folder or the weather card there, make that page the home page, or open settings.
- **Press and hold a dock icon** to change or empty that spot.
- **Tap a folder** to open it. Tap its name to rename it, or use **+ Add apps**.

## Settings

Open from the gear in the app drawer, or press and hold an empty spot.

- Grid size: 5 × 6, 6 × 7, 7 × 8 or 8 × 8.
- Colors for the drawer and folders: Light, Dark or Same as phone.
- Weather town, °F or °C, and which app opens when you tap the weather.
- InkFlow sign in and sign out. The password is used once to get a sign-in token and is not kept on the phone.
- Make Press Sheet the default home app, add a blank page, or start the layout over from the Samsung layout.

## Building

Every push to `main` builds a signed APK on GitHub Actions, runs it on an Android emulator, and publishes:

- the APK as a release `v1.0.N`, and
- screenshots from the emulator under the **Test screenshots** release.

The app is plain Java with only built-in Android parts (no WebView, no extra libraries).
