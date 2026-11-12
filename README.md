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
- **Desktop** — pages you swipe left/right, with app icons and widgets on a
  4×5 grid. Hold an item to drag it; hold an empty spot for widgets,
  wallpaper, pages and settings.

## Build

```
./gradlew :app:assembleDebug
```

Needs JDK 17 and the Android SDK (platform 35). Min Android version: 9 (API 28).
