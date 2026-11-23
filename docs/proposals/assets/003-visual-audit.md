# Bluke visual audit (baseline before the UI overhaul)

Captured from commit ef897d8 (HEAD of `t3/ui-workflows-screen-mirror`) by `001-AllScreensScreenshotTest.kt` (next to this file; copy it into `app/src/test/java/dev/arnv/bluke/` to run it).
Pixel 8 size (411x914dp, 420dpi), SDK 36, Robolectric native graphics, `dynamic_color=false` (the brand palette, Bluke Blue `#0A3D91`) unless the filename says `dynamiccolor`.
The PNGs were not committed; re-run the harness to regenerate them, named `<surface>-<state>-<theme>.png`. `*-full-light.png` are the same settings screens rendered 2400dp tall to show all content.
Contact sheets used for review are in `sheets/`.

**122 PNGs**: home 16 states x 2 themes, input surfaces 13 x 2, media upright 2, layout-switcher overlay 2, onboarding 6 x 2, 16 settings/support activities x (light, dark, full-length light). Nothing failed to render.

Not captured (would need clicks into sub-dialogs): keyboard theme editor, the choice dialogs (typing layout, modifier keys, caps lock, palette style, dev "Home Screen Error State"), touchpad with modifier rail / numpad on, pressed-key and Fn states, the keyboard-themes overflow menu. Robolectric draws no status/navigation bars, so edge-to-edge inset handling is not judged here; AlertDialogs render full-bleed width, which may be a Robolectric artifact rather than the app.

---

## The ten worst problems (ranked)

1. **Dark theme uses the light-mode primary.** `Theme.kt` builds dark mode as `DarkColorScheme.copy(primary = baseColor, primaryContainer = baseColor.copy(alpha=0.3f), onPrimaryContainer = baseColor.copy(alpha=0.9f))` with `baseColor = #0A3D91`. Navy on `#141218` is roughly 1.6:1. Result, across every dark screen: Scan / Connect buttons nearly invisible (`home-paired-list-dark`, `home-connected-dark`), the connected host's name is navy-on-navy (`home-connected-dark`), section headers ("PAIRED DEVICES", "Built-in themes", "Sound profiles") vanish, all list icons in Settings/Look & Feel/Behavior/Touchpad are dark blue smudges (`settings-dark`, `lookandfeel-dark`, `behavior-dark`), onboarding headlines "Permissions Needed"/"How to Connect" are unreadable (`onboarding-2-*-dark`, `onboarding-3-*-dark`), numbered step badges disappear (`help-dark`, `onboarding-3-*-dark`), "Not now" in the gamepad guide dialog is invisible (`home-dialog-gamepad-guide-dark`), the selected sound pack row is navy text on navy (`soundpacks-dark`), slider labels ("Medium") vanish (`touchpadsettings-dark`). This one bug is most of what makes dark mode look broken; a tonal palette (light primary ~`#AFC6FF` in dark) fixes the majority in one place.
2. **The home hero card lies about state.** It reads "Ready to Scan / Scan nearby devices" while Connected, Connecting, and Permission Denied (`home-connected-light`, `home-connecting-light`, `home-permission-required-dark`), and still says "Scan nearby devices" while scanning with no progress indicator (`home-scanning-light`). The only real status is a 12sp chip ("Connected", "Offline" with a *blue* dot meaning idle). The biggest element on the main screen carries the least information; the connected host should be the hero.
3. **Permission Denied has no fix-it path.** `home-permission-required-*` shows a normal list, "No paired devices yet", an active "Launch Keyboard" button, and a small "Permission Denied" chip. Compare `home-bt-off-*`, which correctly blocks with a full-screen state and a "Turn On Bluetooth" button.
4. **Every device row has an identical tonal "▶ Connect" pill.** Three to five identical buttons stacked (`home-paired-list-light`, `home-scanning-light`) read as noise; the row itself should be the tap target with the action implied. The "play" triangle on Connect/Scan is the wrong metaphor. The connecting row shows a grey "Connect" pill and a tiny spinner fragment in the icon slot (`home-connecting-light`) instead of saying "Connecting…". Count text is ungrammatical ("1 devices", `home-connecting-light`).
5. **The input-surface toolbars are 9sp text in 28dp pills.** Keyboard, touchpad, gamepad and media share a black strip of 6-8 equal-weight grey pills (`keyboard-default-landscape-*`, `touchpad-landscape-*`, `gamepad-xbox-*-landscape-*`). Labels at 9sp and lock LEDs at 8sp are below comfortable reading size; tap targets are 28dp (under the 48dp guideline) on a surface used by thumbs while typing. Close, mode, host, LEDs, layout, sound, theme and mute all look the same, so nothing reads as primary. The media toolbar uses different type sizes from the others (`media-landscape-dark` vs `touchpad-landscape-light`), and uses a *mouse* icon for "Multimedia".
6. **Multimedia is an undifferentiated grid of identical grey squares.** Play/pause is the same size and colour as PgDn, Esc, Home, "Black" (`media-landscape-dark`). Volume order is mute / up / down. "Black" is an unexplained label. Mouse buttons under the pad are blank slabs. The upright posture (`media-upright-portrait-*`) rotates all glyphs 90 degrees, truncates labels to "Bla…" and "Ho…", and spends ~60% of the screen on unlabelled empty rectangles.
7. **Gamepad composition is top-heavy and low contrast.** The bottom third of the landscape screen is empty while sticks, bumpers and face buttons crowd the top (`gamepad-xbox-native_hat-landscape-light`). D-pad arrows and stick wells are dark grey on dark grey. The Xbox Guide button uses a Bluetooth-searching glyph. Edit mode outlines and resize handles are navy on near-black (`gamepad-xbox-editmode-landscape-dark`), with no instructions or reset affordance visible. Native/Android/Web profiles are visually identical apart from one pill label, which is correct for the descriptor but means the mode is easy to miss.
8. **Every settings screen is the same stock Material 3 stack, and much of it is filler.** Large top app bar with ~150dp of empty air above the title (`settings-light`), lavender-tinted `surfaceVariant` cards with 24dp corners, a blue icon at left on every row, label-small section headers. Icons are repeated or arbitrary: the same keyboard glyph for Typing layout, Modifier keys, Layouts and every one of the 7 geometry rows (`keyboardsettings-light`, `keyboardlayouts-light`), a gamepad glyph on every row of Input mode cycle including "Keyboard" (`quickcycle-light`), a gear for Caps Lock, a warning triangle for "Hide unnamed devices", a fingerprint for "Show MAC addresses" (`behavior-light`). Controller settings is one row on an empty screen (`controllersettings-*`). The Settings entry says "Behavior" but opens a page titled "Controls & connection", whose section header spells it "Behaviour".
9. **Pickers don't show what you are picking.** Keyboard layouts list text names with an identical icon and "Include in toolbar cycle" repeated seven times, no geometry preview (`keyboardlayouts-light`). Sound packs have no play/preview (`soundpacks-light`). Selection is shown twice (highlighted row plus a check mark) next to an always-on checkbox with a different meaning, so "selected" vs "in the cycle" is unclear (`keyboardthemes-light`, `soundpacks-light`). Look & Feel spends a third of the screen on a paint-roller illustration and pages 20 accent colours four at a time behind dots (`lookandfeel-light`). The "Import pack" FAB sits on top of list rows (`soundpacks-*`).
10. **Onboarding is generic and slow.** Welcome is logo, wordmark and one sentence that only mentions "keyboard" (`onboarding-1-welcome-*`). Page 3 locks the CTA behind a forced 5s countdown (`onboarding-3-connection-guide-countdown-light`). Denied permissions produce a saturated red block (`onboarding-2-permissions-after-deny-dark`). The pager dots are three unrelated shapes (cookie, pebble, arch), which reads as novelty rather than brand.

---

## Per-screen notes

### Home (`home-*`)
- **Eye goes first to** the full-width navy "Launch Keyboard" pill at the bottom, which is right, then to the hero card, which says the wrong thing (problem 2).
- **Hierarchy**: title "Bluke" at titleLarge bold, hero card title at titleMedium, device names at titleMedium bold, so three levels look alike. ALL-CAPS primary-coloured section headers ("PAIRED DEVICES", "ACTIVE CONNECTION") are a Material 2 idiom.
- **Spacing**: list rows are 72dp-ish with a 2dp gap and a shared rounded container, which is good. 16dp gutters. The empty state (`home-empty-*`) is a single line of grey body text under a header with ~550dp of nothing; it should say what to do ("Make your computer discoverable, then Scan").
- **Launch bar**: the 64dp circle to the left cycles modes with only an icon (keyboard / mouse / gamepad / play-in-screen), with no label and no hint that it is a toggle. The launch button uses a "chevron up" icon, which doesn't mean launch. Mode-specific labels ("Launch Multimedia") are fine (`home-connected-launch-*`).
- **Connected row** (`home-connected-light`): primary-tinted container with a red filled "Disconnect" button. The destructive action is the most saturated element on screen while connected; it should be quieter (text button or overflow).
- **Blocking states**: `home-bt-off-*`, `home-unsupported-*` and `home-profile-not-supported-*` are centred icon + headline + body + button. These are clean but generic. Profile-not-supported uses red body text for a paragraph of advice, which reads as an error dump; a callout or secondary text would do. Retry is an outlined button while Turn On Bluetooth is filled, which is inconsistent.
- **Troubleshooting nudge** (`home-troubleshooting-nudge-light`): secondaryContainer card with a help icon, four lines of body text, a lone "Help" text button and a close X. Too wordy for a nudge.
- **Dialogs** (`home-dialog-descriptor-refresh-*`, `home-dialog-gamepad-guide-*`): stock AlertDialogs with paragraph walls (the descriptor one is 7 lines). Fine as a one-off, but this is where a short checklist with steps would help.
- **Dynamic colour** (`home-connected-dynamiccolor-*`): with Robolectric's default wallpaper palette, the launch button turns a desaturated slate and the screen loses all brand. Dynamic colour is on by default for SDK 31+ users, so real users mostly see a wallpaper-derived palette, not Bluke Blue. The overhaul should decide whether that is wanted.

### Keyboard (`keyboard-*-landscape-*`, `keyboard-layout-switcher-overlay-*`)
- The strongest screen in the app. Keycaps have a convincing two-tone sculpt, accent keys (Esc, Enter, arrows) are clear, legends are well placed, and shifted symbols are rendered top-left.
- Light and dark are identical by design (keyboard theme, not app theme).
- Legend sizes are inconsistent: "Delete" and "Backspace" shrink to fit while other modifiers stay large (`keyboard-hhkb60-landscape-light`).
- HHKB 60% doesn't use the available height, leaving ~90dp bands of empty case above and below. The default geometry fits well.
- Toolbar: see problem 5. Theme name "Oblivion" and sound "Cherry MX Browns" occupy prime space for settings that are rarely changed mid-typing.
- Layout switcher overlay: a frosted, translucent lavender card over the keys, with the selected layout ("AZERTY") in a tonal pill and the host hint. It reads well, but the translucency lets the keycap legends ghost through behind the labels.
- `keyboard-default-portrait-*`: the app forces landscape, so this is not reachable in practice. If the orientation request is ever ignored (e.g., by a foldable or desktop mode), the keyboard shrinks to a strip and the toolbar clips.

### Touchpad (`touchpad-landscape-*`)
- One huge empty outlined rectangle. There are no click buttons, no hint text ("two-finger scroll, tap to click"), and no scroll strip. Its toolbar has 7 pills ("Modifiers off", "Numpad", "Clickpad", "Pointer 1.5x", "Scroll 1.0x"), which reads like a debug panel.
- `touchpad-portrait-*`: unreachable (forced landscape), and the toolbar is clipped.

### Gamepad (`gamepad-*`)
- See problem 7. The PS5 layout (`gamepad-ps5-native_hat-landscape-light`) has better balance than Xbox: D-pad and stick swap places, and the symbols are correctly coloured.
- L3/R3 badges overlap the stick rings.
- `gamepad-portrait-*`: unreachable, and the controls squash and overlap there.

### Multimedia (`media-landscape-*`, `media-upright-portrait-*`)
- See problem 6. The landscape split (button grid left, pointer pad and scroll strip right) is a sound structure. It needs grouping (transport, volume, navigation, presentation), size hierarchy (play/pause and arrows bigger), and labels on the mouse buttons.

### Onboarding (`onboarding-*`)
- See problem 10, plus problem 1 for dark mode.
- Permissions page: two list rows explaining Bluetooth and Location with a filled "Grant Permissions" CTA. The content is fine; the 120dp blank band above the headline is not.
- The granted state just changes the CTA text to "Continue", with no confirmation tick on the rows (`onboarding-2-permissions-granted-*`).
- Connection guide: two numbered cards (New / Existing device). Good content, but small 13sp-ish body text.

### Settings root (`settings-*`)
- Large-title app bar, then three groups of tall two-line cards. The subtitles repeat the pages' contents ("App colors, dark theme, haptics, and key sounds" and "Typing behavior, layouts, themes, and key sounds" both list key sounds). Only about 4.5 rows fit above the fold on a Pixel 8 (`settings-light`); see `settings-full-light` for the whole page.

### Look & Feel, Dark theme (`lookandfeel-*`, `darktheme-*`)
- See problem 9 for the colour pager. Dark theme is a separate page holding three radio rows and one toggle, which could be a segmented control inline on Look & Feel. With dynamic colour on, the accent row presumably does nothing, but it stays visible here.

### Keyboard settings, Layouts, Themes, Sounds (`keyboardsettings-*`, `keyboardlayouts-*`, `keyboardthemes-*`, `soundpacks-*`)
- "Key touch area" uses an M3 expressive stepped slider with a "Balanced" caption in primary. Reasonable, but the caption floats left under the track rather than next to the value.
- Themes list: colour-swatch clusters are a nice touch, and the only place where list rows carry real visual information. In dark mode, the first swatches (black case and keycaps) disappear into the card (`keyboardthemes-dark`).
- The headers above Themes and Sound packs are three-line paragraphs explaining the model. That signals the UI is not self-explanatory.

### Touchpad / Controller / Behavior (`touchpadsettings-*`, `controllersettings-*`, `behavior-*`)
- See problem 8. The touchpad page ends with a footnote ("Scroll speed is the arrows button on the touchpad's toolbar"), which documents a missing setting instead of providing it.

### Input mode cycle (`quickcycle-*`)
- Four rows with the same gamepad icon and checkboxes. The checkbox convention differs from the M3 switch used elsewhere for on/off choices.

### Help (`help-*`)
- Numbered-step cards with a coloured circular icon, plus a red-tinted "Pairing was refused" card. This is the best-structured of the support pages. It is long (`help-full-light` is ~5800px), with no index or collapsible sections.

### About, Licenses (`about-*`, `licenses-*`)
- About: the logo is centred with the version and two cards. The developer shown is the upstream author (`arnav-kr`), which is appropriate for a fork but worth a look.
- Licenses uses a *small* top app bar, while every other page uses a large one. Its header card is a mid-blue block with navy text (low contrast even in light mode, `licenses-light`). The library avatars are letter circles.

### Developer options and logs (`developeroptions-*`, `developerlogs-*`)
- An ALL-CAPS "WARNING:" banner, a bare Developer Mode switch row outside any card, then cards. The "None" value sits right-aligned in primary as a pseudo-button.
- Logs: small top app bar with three icons, an outlined filter field, then an empty area with no empty-state text.

---

## Cross-screen consistency

- Two app-bar styles: large on most pages, small on Licenses and Developer Logs.
- Three control vocabularies for on/off: switches (settings), checkboxes (cycles, layouts, sounds), radios (dark theme).
- Two visual languages that don't meet: the immersive dark "hardware" surfaces (keyboard, gamepad, media) and the default lavender M3 settings. The home screen, where they meet, adopts neither. A unified direction would carry the hardware surfaces' material honesty (dark chassis, real keycaps, LED dots) into home and settings, rather than the reverse.
- Light mode uses M3's default lavender-tinted surfaces (`#FFFBFE` family) against a navy primary, which reads slightly purple-washed. The neutrals are untuned.
- Typography is all Roboto at M3 default sizes. No custom type scale and no tabular figures.

## What a polished app would do differently

- **Things / Linear mobile**: one strong focal object per screen (here, the connected host card with a large status and one primary action). Quiet secondary rows without per-row buttons. Grouped lists with hairline separators, not floating tinted cards. Tight 4/8 spacing with no empty large-title bands.
- **Apple Settings**: inset grouped lists, coloured rounded-square icons that encode category (one colour per section), values shown trailing in secondary text, and pickers shown inline as checkmark lists. "Behavior" would be the page's title.
- **Nothing OS**: monochrome base plus one accent, dot-matrix or mono type for status ("CONNECTED · MACBOOK PRO"), and LED-style indicators. That vocabulary already fits Bluke's keyboard chassis, LED lock dots and toolbar, and it would make the brand distinct instead of stock Material.
- On the input surfaces: a slim auto-hiding toolbar with 2-3 primary items (close, mode, host status), with the rest behind one "more" control at 44-48dp targets; hint text inside empty surfaces; and size hierarchy on the media grid.

---

## Harness: how it works and what it took

`AllScreensScreenshotTest.kt` uses `createEmptyComposeRule()` + `ActivityScenario` + Roborazzi `captureScreenRoboImage` (experimental API; it captures dialogs, which `onRoot().captureRoboImage` would miss). Run it with `./gradlew recordRoborazziDebug --tests 'dev.arnv.bluke.AllScreensScreenshotTest'`. All 122 shots take ~28s warm. Theme is switched per shot via the `theme_mode` preference plus `RuntimeEnvironment.setQualifiers("+<size>-night|notnight")`, and orientation via `w914dp-h411dp-land` qualifiers.

What it took:
- **HomeScreen is not stateless.** It takes the real `BluetoothKeyboardManager` and builds its own `HomeViewModel`, so fake state required **reflection on 8 private `MutableStateFlow`s** (`_serviceState`, `_statusMessage`, `_bondedDevices`, `_scannedDevices`, `_isScanning`, `_connectedDevice`, `_connectionTargetAddress`, `_lifecycleState`), set before and after composition because the manager's init writes its own state. Devices are Robolectric shadow `BluetoothDevice`s with names and bond state, plus granted BLUETOOTH_CONNECT so `.name` works.
- **The input surfaces live inside HomeScreen** behind a private `isKeyboardActive`. Reaching them means setting `launch_mode` in prefs and clicking `start_keyboard_btn`. Gamepad console and edit mode are reached by clicking `console_selector_pill` and "Edit Layout". TouchpadView, GamepadView and MediaPresentationView can be called directly, but then they lose the forced dark scheme HomeScreen wraps them in.
- **Every settings screen is inline in `Activity.onCreate { setContent { … } }`**, with no `XScreen(state, callbacks)` composable (only `OnboardingScreen` is separable), so they are captured by launching the Activity. That works, but it only captures default preference state, and it can't render a variant without writing prefs first.
- **Dialogs and banners are driven by prefs** (`mock_troubleshooting_nudge`, `has_seen_gamepad_guide`, the HID descriptor revision), so each state is a prefs recipe.
- **Several UI facts live in SharedPreferences read during composition** (`MyApplicationTheme` itself reads `app_prefs`), so every shot must reset prefs. `baselinePrefs()` does this.

To make this a clean scoring loop for the UI lead:
1. Split `HomeScreen` into a stateful wrapper and a stateless `HomeContent(uiState: HomeUiState, launchMode, onScan, onConnect, …)`. `HomeUiState` already exists, so this is mostly moving code. Then home states are plain data, with no reflection.
2. Give each settings Activity a `XxxScreen(state, callbacks)` composable and keep the Activity as a thin host.
3. Give `MyApplicationTheme` explicit parameters (`darkTheme`, `dynamicColor`, `accent`) with the prefs reading in a wrapper, so tests and `@Preview`s don't go through SharedPreferences.
4. Make `KeyboardToolbar` / `InputSurfaceToolbar` its own composable, shared by the four surfaces (today the pill row is hand-built inline in HomeScreen.kt, TouchpadView.kt and GamepadView.kt, and MediaPresentationView.kt has its own variant, which explains the inconsistencies in problem 5).
5. Keep the `<surface>-<state>-<theme>` naming and commit a curated subset as baselines under `app/src/test/screenshots/` for `verifyRoborazziDebug`.
