# Press Sheet

A home screen (launcher) for John's Samsung phone, built for The Ink Stop.

Your apps are sorted into labeled sections so everything stays visible as an icon and easy to find:

- **Page 1:** your everyday apps (Camera, Photos, Calendar, Maps, Notes, Clock, Calculator, Gemini), the weather + InkFlow card, then **Shop** and **Reselling**.
- **Page 2:** **Home & family**, **Money & bills** (with the Banking folder) and **Shopping & food** (with the Food & rides folder).
- **Page 3:** **Games** (with the Nintendo folder), **Tools** (with the AI folder) and **Watch & listen**.
- **Dock:** Phone, Messages, InkStop Mail, Chrome, Starbucks.
- **Swipe left and right** between pages; a page that's too tall scrolls up and down.
- **Swipe up** for every app: search, recently used, then A to Z. Search also finds InkFlow pages and can search the web.
- **Swipe down** to pull down notifications.
- **Home button** closes whatever is open and goes back to page 1.

## Changing the layout

- **Tap a section heading** to add or remove apps, add a folder, rename it, move it up, down or to another page, add a new section below, or remove it.
- **Press and hold an icon** to move it to another section, move it left or right, put it in a folder, remove it, or see app info.
- **Press and hold an empty part of a page** to add a section, bring back the weather card, add a page, or open settings.
- **In the app list,** press and hold any app to add it to a section or the dock.

## Settings

Open from the gear in the app drawer, or press and hold an empty spot.

- Icons across: 5, 6, 7 or 8.
- Colors for the drawer and folders: Light, Dark or Same as phone.
- Weather town, °F or °C, and which app opens when you tap the weather.
- InkFlow sign in and sign out. The password is used once to get a sign-in token and is not kept on the phone.
- Make Press Sheet the default home app, add a blank page, or start the sections over.

## Building

Every push to `main` builds a signed APK on GitHub Actions, runs it on an Android emulator, and publishes:

- the APK as a release `v1.0.N`, and
- screenshots from the emulator under the **Test screenshots** release.

The app is plain Java with only built-in Android parts (no WebView, no extra libraries).
