# UI scoring rubric (1-10) for the Bluke UI specs

Built from the installed design skills (impeccable, material-3, apple-design) and Make Interfaces Feel Better. A lead hill-climbs against this until a fresh reviewer scores its branch 9 or higher.

## Evidence the reviewer must have

A score given without this evidence is "not scorable", not a low number. The lead adds the Roborazzi tests. Today `app/src/test/screenshots/` holds 4 baselines, which isn't enough.

1. Screenshots of every screen the change touches:
   - **light** and **dark**;
   - **dynamic color off** (deterministic; the static schemes are what fail);
   - **font scale 1.0 and 1.3**, plus 2.0 for text-heavy settings screens. Set it via `CompositionLocalProvider(LocalDensity provides Density(density, fontScale = …))`;
   - **portrait and landscape** for the input surfaces;
   - Bluke's real states: no host, connecting, connected (long host name), reconnect pending, profile-not-supported, LED (caps lock) on, each gamepad profile.
2. The same captures from `main`, side by side, so regressions are visible.
3. Motion. Still images can't show it, so the reviewer gets:
   - the code;
   - and/or filmstrips: Roborazzi captures at fixed `mainClock.advanceTimeBy` steps with `autoAdvance = false`, for each changed transition and press state.
4. The diff. Haptics, semantics and touch-target sizes are judged from code.
5. What could not be judged from the emulator: gesture feel, frame rate, haptic strength. The reviewer lists these as **Not verified**; they never count as passes.

## Seven dimensions, each scored 0–4

Each dimension names the skill it comes from.

| Dimension | 4 = excellent | 2 = partial | 0 = broken |
|---|---|---|---|
| **A. Platform and theme conformance** (material-3, Impeccable `android.md`) | Every color is a `colorScheme` role, with `on-X` paired on `X`. Content colors (gamepad ABXY faces, keyboard themes) live in one named token object. Type comes only from `MaterialTheme.typography` roles in sp. Shapes come from `MaterialTheme.shapes`. Elevation is tonal. Dark is designed, not inverted. Dynamic color has a static fallback that passes contrast. Edge-to-edge insets are handled. Predictive Back works. | Mostly roles, but some raw `Color(0x…)` or `.copy(alpha=)` stand in for roles. Some hand-picked sizes. | Hardcoded palette throughout, alpha-faked containers, broken dark theme. |
| **B. Hierarchy and layout** (Impeccable cognitive load and Operate mode; Make Interfaces Feel Better) | The screen's one job is obvious in under 5 seconds. One primary action. At most 4 choices at any decision point; the rest are grouped. Spacing is on an 8dp rhythm (4dp for fine steps). Nested radii are concentric (outer = inner + padding). Icons are optically centered. Content width is capped in landscape. No card inside a card. | Clear main content, noisy edges. Uneven spacing. One or two concentric or alignment misses. | Everything carries the same weight, or there are walls of options and cards in cards. |
| **C. Status and feedback** (Nielsen #1 and #9, Impeccable) | The connection state, which host, and the input mode are always visible without scrolling. Every action confirms itself. Errors name the cause and the fix (for example, "unpair and re-pair after the descriptor update"). Empty, loading and error states are designed. | States exist, but some are vague ("Something went wrong") or appear only in logs. | You can't tell whether the phone is connected. |
| **D. Touch and feel** (apple-design §1–3, 10, 13) | Visual feedback on touch-down, same frame. The touchpad and sliders track 1:1. Haptics fire on the causal event, in the same frame as the visual, and only where they earn it: key press, mode switch, snap, error. All targets are 48dp or more, at least 8dp apart, and clear of the system gesture zones. Draggable things rubber-band and inherit release velocity. | Feedback appears on release, or haptics are inconsistent across surfaces. A few targets under 48dp. | Laggy taps, dead zones, haptics everywhere or nowhere. |
| **E. Motion** (Emil `review-animations`, apple-design §4–9, M3 motion) | Every animation has a reason (spatial, state, feedback). Repeated actions such as key presses and touchpad moves get **no** decorative animation. Springs or M3 `MotionScheme` are used. Motion can be interrupted and retargets from the current value. Enter and exit follow the same path. UI motion stays under 300 ms except sheets. Nothing grows from scale 0. It respects the system animator duration scale and "Remove animations". | Motion fits but feels sluggish (`tween` 400–500 ms on everyday UI), or some can't be interrupted. | Animations block input, fire on every key, or ignore reduced motion. |
| **F. Accessibility and robustness** (Impeccable `audit.native`, material-3 accessibility) | Text contrast at least 4.5:1, icons and outlines at least 3:1, in both themes and the static schemes. Every control has TalkBack semantics: role, state, and a `contentDescription` for icon-only controls. Nothing clips at font scale 1.3, and settings don't clip at 2.0. Long or emoji host names ellipsize cleanly. Landscape works. | One theme or one scale breaks. A few unlabeled icons. | Contrast fails on primary content, or text clips at 1.3. |
| **G. Specificity and restraint** (Impeccable "design specificity", frontend-design) | It reads as Bluke, a controller you hold, not a generic settings template. Boldness is spent in one place (likely the input surfaces); everything else is quiet. No decoration without a job. Copy is plain and consistent ("host", not a mix of host, computer and device). | Competent but generic, or two competing focal points. | Templated or noisy: gradients, emoji icons, invented badges. |

## From dimension scores to 1–10

Add the dimension scores (out of 28). Then apply the **caps**: a cap overrides the sum.

| Score | Must hold |
|---|---|
| **10** | All seven dimensions 4/4. No P0–P2 findings. Every state captured. Gesture feel and haptics checked on a real phone. A designer would struggle to find anything. |
| **9** | **No P0 or P1.** At most 2 P2s, each with a concrete fix. Every dimension at least 3. At least four at 4, and **D (feel) and C (status) both at 4**. Captures cover light and dark, 1.3 font scale, landscape, and the connection states. No visible regression against the `main` captures. Anything not verifiable on the emulator is listed and is not a P1 risk. |
| **8** | No P0 or P1. Every dimension at least 3. Sum at least 23. |
| **7** | No P0. At most one P1. Sum at least 20. **Any P1 caps the score at 7.** |
| **5–6** | No P0. Several P1s or a dimension at 2. Works, but plainly unpolished or inconsistent. |
| **3–4** | **Any P0 caps the score at 4.** P0 means: a contrast failure on primary text or controls; text clipped at font scale 1.3; a control without TalkBack semantics on a primary surface; connection state not visible; input latency added by animation; a broken dark theme. |
| **1–2** | Broken layouts or unusable surfaces in the default configuration. |

Severity is Impeccable's scale:
- **P0** blocks use or accessibility.
- **P1** is a clear defect most users would notice.
- **P2** is polish a careful user notices.
- **P3** is a nitpick and doesn't count toward the score.

## Reviewer protocol (so the hill-climb doesn't game itself)

- **Use three fresh Opus reviewers every round.** Each gets the evidence pack and this rubric, but not the builder's self-score, the target, or earlier review text. Each scores `main` first as the baseline, then the branch.
- **The round's score is the median of the three.** One reviewer's draw can't make or break a round. The builder fixes every P0 and P1, and every P2 that two or more reviewers raise. A P2 raised by only one reviewer is fixed only when it's cheap.
- **Every finding carries location, before, after and why.** The location is `file:line` or the screenshot name. Findings are grouped by dimension. Each dimension's score cites the findings that set it. A 4 needs a named positive, not just an absence of findings.
- **Run material-3's `audit` and Impeccable's `critique` as the two raw inputs.** Map their totals onto dimensions A, B, C and F. The 1–10 score is the reviewer's judgment through the caps above, not an average of the two tools.
- **State what wasn't seen** (motion on device, haptics) instead of assuming a pass.

## Calibration: what a reviewer would flag in today's code

Found in a quick pass; these are not a full review.

- **Static dark primary fails contrast.** In `app/src/main/java/dev/arnv/bluke/ui/theme/Theme.kt`, the static dark scheme's primary `#0A3D91` on surface `#141218` is **1.85:1**. Any primary-tinted icon, outline or text on the surface fails 3:1 when dynamic color is off. That's a P0 under F/A.
- **Alpha-faked containers break tonal pairing.** At lines 128 and 137, `primaryContainer = baseColor.copy(alpha = 0.3f)` with `onPrimaryContainer = baseColor.copy(alpha = 0.9f)`. That's P1 under A.
- **Pure-black dark variant with a non-role gray.** Line 123 sets `background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1C1C1E)`. M3 advises against pure black, and `#1C1C1E` is an iOS gray, not a tonal role. P2 if kept on purpose as an AMOLED option.
- **Raw hex in UI files.** `Color(0x…)` appears 55 times in `GamepadView.kt` and 70 times in `KeyboardLayouts.kt`. Fine if these are content tokens gathered in one place. A finding wherever they stand in for theme roles.
- **Haptics in one file only.** `HomeScreen.kt` is the only UI file calling haptics. Check D on the keyboard, touchpad and gamepad surfaces.
- **Settings groups don't use theme shape tokens.** In `SettingsComponents.kt` they use hand-rolled 28dp and 4dp corners with `surfaceVariant.copy(alpha = 0.5f)` instead of `shapes` and a `surfaceContainer*` role. A, P2.
