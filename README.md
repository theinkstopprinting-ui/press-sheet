# Press Sheet

A native Android home screen (launcher) with two looks, picked in Settings:

- **Glance**: your wallpaper, a big clock, the weather, InkFlow at a glance, 4 favorites and the dock.
  Everything else is one swipe up.
- **Press Bed**: clock and weather, an InkFlow strip, then one bold CMYK band per category
  (Shop, Reselling, Home, Family, Games, Money, Shopping & food, Everyday), every app on one screen.

Both share the swipe-up drawer: search (apps, InkFlow pages, the web), recently used apps,
category tabs and an A to Z list with a letter scroller. Light, dark or same-as-phone colors.

Press and hold an app, a category name, a favorite or a dock spot to change it.

## Built with

- Plain Java and Android's own views (no web page inside), so it opens instantly when you press Home.
- Weather from the US National Weather Service (api.weather.gov, free, no key, official alerts),
  with Open-Meteo as the backup. Town search uses Open-Meteo's place search.
- InkFlow: signs in once with a staff login (`/api/mobile/login`), then reads `/api/mobile/launcher`.
  The password is not kept on the phone.
- Fonts: Bricolage Grotesque, DM Sans, Fraunces and Inter Tight (SIL Open Font License).

Every push to `main` builds a signed APK on GitHub and publishes it under Releases, then installs it on an
emulator and saves screenshots of both looks under the "Test screenshots" release.
