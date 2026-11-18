# Press Sheet

A home screen (launcher) for Android with a big clock and five modes you switch by hand:

- **Shop**: InkFlow with today's orders, live printers, InkStop Mail and shop tools
- **Home**: Family Hub, Jellyfin, Immich, FretQuest and the rest of the house
- **Reselling**: Lister, Whatnot and the marketplaces
- **Games**: Disney Solitaire, Coin Master, Video Poker and the rest
- **Financial**: PayPal, insurance, health benefits and fuel

Tap the clock to open your Clock app. Press and hold any tile, button, dock spot or the clock to change what it opens, its colour or its size.
Swipe up (or tap **All apps**) for every app with search; the gear there opens Settings.

## How it's built

- `app/src/main/assets/www/` is the whole interface (HTML, CSS, JavaScript). Opened in a desktop
  browser it runs with sample apps, which is how the layout is tested.
- `MainActivity.java` shows that page in a web view that only loads the bundled files, and is the
  bridge to the phone: the app list, icons, opening apps, the saved layout.
- The only network use is asking your InkFlow for the Shop tiles' numbers
  (`/api/mobile/login` once, then `/api/mobile/launcher`), with the same kind of 30-day sign-in
  InkStop Mail uses. The password is not kept on the phone.

Every push to `main` builds a signed APK on GitHub, publishes it under Releases, then installs it
on an emulator, opens it and saves screenshots.

Fonts: Bricolage Grotesque and DM Sans (SIL Open Font License, see `assets/www/fonts`).
