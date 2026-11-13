# Lazy9

A minimalist Android launcher built around T9 app search.

- **Dock** — five slots: four apps and the T9 search button in the middle.
  Hold an empty slot to assign an app.
- **T9 search** — a small card with a keypad; type the app's name on the
  digits (`9688` → YouTube). Shows the three best matches, ranked by how often
  you launch them. `1` clears the search.
- **Swipe up** — all apps. Hold an app for its shortcuts, App info, Uninstall,
  Add to home screen / dock.
- **Swipe down** — notifications.
- **Double tap** on the desktop — screen off (needs the launcher's
  accessibility service; it is used for nothing else).
- **Drag and drop** — hold an icon and move it: between the desktop and the
  dock, out of the app list onto either, or onto *Remove* at the top.
- **Desktop** — pages you swipe left/right, with app icons and widgets on a
  4×5 grid. Hold an item to drag it; hold an empty spot for widgets,
  wallpaper, pages and settings.
- **Settings** (hold the desktop → Launcher settings) — optional extras:
  - *Persistent search*: the keypad stays on the home screen, with a column of
    three apps beside it instead of the dock.
  - *T9 card appearance*: colour, opacity, width, key height and the size of
    digits and letters, edited over the live card.
  - *Monochrome icons*: one-colour icons in the wallpaper palette.
  - *Favourite contacts in T9*: starred contacts show up in the results; tap
    one to call, hold it for SMS and whatever your messengers (Signal,
    WhatsApp, Telegram, …) offer for that contact. Needs the contacts and phone permissions, asked for only when
    you switch it on. Contacts stay in memory — nothing is stored or sent.
  - *Export / import settings*: the whole layout and every option as one JSON
    file you keep wherever you like.

## Build

```
./gradlew :app:assembleDebug
```

Needs JDK 17 and the Android SDK (platform 35). Min Android version: 9 (API 28).
