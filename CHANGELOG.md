# Project History & Milestones 📜

**Project Persona**: Smart Edge: Sidebar & Gestures — Floating overlay service launcher
**Tech Stack**: Kotlin, Android SDK 26+, Material3, ViewBinding, DynamicAnimation, Glide
**Design Language**: Material You (M3) + Custom Glassmorphism

---

### Unreleased
- **ci**: Pull requests to `master` now fail a `Changelog` check unless they add a bullet under `### Unreleased` (opt out with the `skip-changelog` label), so every tag has its release notes ready.
- **fix(review)**: Full-codebase review fixes (17 Medium, 36 Low): double screenshot no longer leaves overlays invisible, notch re-gated on rotation, ToggleActivity and boot auto-start work for automation-only users, Black Screen timeout gated on WRITE_SETTINGS, tap+long-press no longer fires both actions, icon-pack fallback is first-wins with per-activity resolution, sidebar load no longer aborts on a dead PackageManager call, language setting applied on all activities, settings search no longer offers hidden rows, and the changelog check now passes release PRs.
- **feat(locale)**: Declared `android:localeConfig` (`res/xml/locales_config.xml`, en/es), so the system per-app language picker lists the app and AppCompat's `setApplicationLocales` works on API 33+. `LocaleHelper.onAttach` now prefers AppCompat's locale (what the system picker sets) with the stored in-app preference as fallback, the in-app language setting shows the effective locale, and the stored preference is synced with AppCompat at process start. The manual wrap stays for Services and the Application, which AppCompat does not cover.
- **fix(audit-4)**: Fourth-audit fixes (2 High, 6 Medium, 7 Low): locale now reads/writes `LocaleManager` directly on API 33+ (the AppCompat static getters only work while an Activity delegate is alive, so the fbda333 startup sync was a no-op and the first screen used the stored pref); the 'engine lost' Snackbar is replaced by the indicator overlay (Snackbar on a WindowManager view threw `IllegalArgumentException`); handles re-gate when the engine probe lands after a reboot; the tap state can no longer stick after a wiggly second tap; a slide reversal discards the stale backlog instead of running the wrong way; custom-row text taps reach the row click; recents validation runs on `Dispatchers.IO` with a per-package cache and stops at the limit; the shortcut token no longer leaks through `CREATE_SHORTCUT` (per-shortcut IDs instead); pre-v1.5.6 shortcut pins are migrated to the canonical intent URIs; `POST_NOTIFICATIONS` is requested when the notification card is granted one at a time; the settings search index is cached across resumes (0 inflations on a plain resume); the manual release now tests the tag it signs; the changelog check requires a real bullet and the release fails fast on a missing section before signing. 12 new unit tests (locale order, lift drop, volume no-fallback).

### v1.5.8
- **fix(sidebar)**: The volume indicator no longer sticks on screen. A second update in the same frame as the first used to add a second overlay window (the first wasn't attached yet), orphaning the first one so its fade never ran; the overlay is now tracked by its field alone. Taking a screenshot mid-fade no longer leaves the indicator visible.
- **fix(sidebar)**: Volume no longer jumps ~10% at random. A shell set that reported failure (timeout, killed process, read-back race) fell back to a relative adjust (~10 indices on OnePlus) on top of a set that may already have landed; the relative adjust now runs only when no Shizuku/root engine exists. Lifting the finger after a fast slide lands at most one capped step batch instead of the whole held-back backlog, and the main-thread set is skipped while shell batches are in flight so the two can't race.

### v1.5.6
- **fix(audit)**: Third independent audit (16 findings) fixed: `LocaleHelper` no longer freezes startup orientation/night mode (the handle reappeared in landscape and game detection never fired); the shell volume drain lands at most 4 steps per batch so a fast slide's backlog no longer keeps landing after the finger lifts, and the volume indicator now refreshes after every batch instead of freezing after the first; the notch handle is gated on the same policy as the edge handle (landscape, home-only, engine), a temp show no longer installs it permanently, its window shrinks from the 120x60dp debug size to a 48x32dp target, and game state doubles its long-press delay; a freshly created hidden edge handle is no longer left touchable; the overlay root is pinned to LTR; sidebar reorder preserves pins that can't currently be shown; shortcut `<categories>`/`<extra>` are only collected inside `<intent>`; the settings-search dangling "Category • " bullet is gone; tapping "Loading…" in the app picker no longer crashes; `SecureSettingsDialog` collects in `repeatOnLifecycle` so it can't dismiss after state is saved; and the startup `setApplicationLocales` call (a no-op on API 33+) is removed.
- **fix(build)**: The release workflow now fails instead of silently publishing the Unreleased notes when a tag has no changelog section (v1.5.4 and v1.5.5 shipped byte-identical bodies); a manual release builds the tag it publishes (`ref: inputs.tag`), and `inputs.tag` is validated and read via an env var instead of being interpolated into the shell script. `gradlew` is executable again on a fresh clone, and `lifecycle-runtime-ktx` (2.6.1) is declared explicitly so `repeatOnLifecycle` resolves.

### v1.3.7
- **fix(ui)**: Prevented custom URL text from being silently discarded when the row scrolls offscreen in the app picker.
- **fix(core)**: Added `RECEIVER_EXPORTED` flag to package receiver for Android 14+ compatibility.
- **fix(core)**: Avoided `ForegroundServiceStartNotAllowedException` on Android 12+ by dispatching toggle on main thread.
- **fix(memory)**: Switched Glide context to `applicationContext` to prevent memory leaks after native view destruction.
- **feat(security)**: Added scheme allowlisting and input sanitization for custom URL/intent field with toast validation.
- **chore(maintenance)**: Rewrote legacy `startAction` logic, eliminating deprecated `Intent` overloads and system dialog broadcasts.
- **chore(maintenance)**: Stripped out unreachable dead-code branches for Android versions below SDK 26.
- **chore(maintenance)**: Removed deprecated `View#setDrawingCacheEnabled` call.

---

### v1.3.6
- **feat(interaction)**: Reorganized Interaction settings dashboard into logical, consolidated groups (Main Trigger, Specialized Interaction, Panel Experience, Multitasking, Shortcuts, Advanced, System).
- **feat(interaction)**: Restored missing Panel Experience features (Landscape, Notification Apps, Scroll Memory, Keyboard) and System & Behavior features (Auto-start, Debug Logs, Animation Feel, Picker Gap).
- **feat(shortcuts)**: Restored Desktop Shortcuts feature to pin a Toggle Sidebar shortcut.
- **refactor(ui)**: Refactored InteractionSettingsActivity to fully utilize ViewBinding.
- **refactor(core)**: Centralized action execution logic into a new ActionDispatcher class to unify Edge and Notch handle behaviors.
- **fix(core)**: Added a mandatory engine check (Accessibility or Automation) in FloatingPanelService before displaying the handle.
- **fix(ui)**: Resolved crash in Handle settings by expanding vertical offset range and safely clamping out-of-bounds positions.
- **fix(multitasking)**: Restored the missing Multitasking & Windowing settings section to the Interaction dashboard. (Correction: the Freeform window and Split-screen launching this entry originally described was later removed — drag-to-split proved unreliable on Android 14+ — so the original windowing claims no longer apply.)
- **fix(ui)**: Fixed invisible pill graphic issue by using correct system defaults for reset actions and forcing synchronous redraws.
- **feat(automation)**: Improved Native Gesture Engine dialog feedback to accurately and instantly reflect Root/Shizuku active connection states.

---

### v1.3.5.1
- **feat(handle)**: Restored 'Move Handle' drag feature and set it as the default long-press action.
- **feat(ui)**: Restored independent pill color customization with a modern color picker and reset option.
- **feat(localization)**: Added Spanish localization and a new in-app language selector in Miscellaneous settings.
- **feat(gestures)**: Added Notch Gestures for camera cutout interaction (Tap, 2x, 3x, Hold).
- **feat(sidebar)**: Added 'Only on Home Screen' mode to hide sidebar/handle inside other apps.
- **feat(tools)**: Added new dashboard tools: Camera, Flashlight (with system sync), and Auto-Rotation.
- **fix(handle)**: Significant stability improvements for handle dragging with absolute-to-relative coordinate mapping.
- **fix(ui)**: Resolved handle drift and "slow sliding" issues by silencing system animations during drag.
- **fix(tools)**: Resolved flashlight toggle desync and edge side switching glitches.
- **fix(ui)**: Harmonized UI across settings, miscellaneous sections, and improved overall visual consistency.

---

### v1.3.5
- **feat(gestures)**: Added 'Long Press' gesture support to the edge handle. You can now configure a specific action (like Go to Home) for a long press.
- **feat(animation)**: Restored 'Slide' as a selectable animation style and made it the default for a smoother entry.
- **fix(animation)**: Resolved issue where the main sidebar would always slide even if 'Popup' was selected.
- **fix(ui)**: Fixed false-negative 'Accessibility Required' warning when using Shizuku-based Native Gestures.
- **feat(ui)**: Re-introduced Animation Style selection in Interaction Settings.
- **fix(animation)**: Unified animation logic between the main panel and app picker to respect user preferences consistently.
- **feat(ui)**: Added search functionality to all App Selectors (Hide Apps, Game Mode, Pinned Apps).
- **feat(icons)**: Modernized Icon Pack selection UI with a sleek BottomSheet design.
- **feat(gestures)**: Added split-screen support to the Native Gesture Engine.
- **feat(gestures)**: Implemented Native Gesture Engine (via Root or Shizuku) for seamless system navigation, bypassing traditional accessibility delays.
- **feat(gestures)**: Improved swipe responsiveness and added a customizable swipe sensitivity setting.
- **fix(ui)**: Resolved pill flickering and ghosting issues during rapid interactions.

---

### v1.3.4
- **feat(gestures)**: Added advanced tap gesture actions including Back, Home, Recents, Notifications, Quick Settings, Lock Screen, and Power Menu.
- **feat(gestures)**: Added fully configurable multi-tap gestures (single, double, and triple tap).
- **feat(sidebar)**: Added ability to manually select specific apps to hide the sidebar in (replaced automatic immersive mode detection).
- **feat(sidebar)**: Added a dedicated tools folder and navigation system.
- **fix(icons)**: Fixed "blank clickable icons" issue by disabling problematic RecyclerView optimizations and implementing explicit state clearing.
- **feat(icons)**: Implemented high-definition, super-sampled icon rendering for perfectly smooth "Circle" shapes.
- **fix(icons)**: Improved real-time settings refresh using a global appearance tracking key.
- **fix(ui)**: Fixed stuck drag shadow in freeform/drag-to-split operations.
- **fix(sidebar)**: Resolved glitchy app sorting and reordering in the sidebar.
- **fix(service)**: Prevented unintended service restarts when disabled and cleaned up debug artifacts.
- **chore**: Updated default settings to enable Dashboard/Tools by default, disabled multi-tap/remember-scroll by default.
- **feat(support)**: Added WebMoney (WMZ) donation option to the Support screen.

---

### v1.3.3
- **feat(tile)**: Completely rewrote Quick Settings Tile — now matches the Start button behavior exactly with a reliable, seamless toggle.
- **feat(tile)**: Optimistic UI update — tile switches state instantly on tap, with no visible delay.
- **feat(tile)**: Haptic feedback on Quick Tile click for tactile confirmation.
- **feat(tile)**: Fixed cross-process SharedPreferences desync on OEM devices (Vivo, MIUI, ColorOS) causing tile to auto-revert to OFF.
- **feat(tile)**: Seamless shade behavior — toggling the tile no longer forces your current app to close.
- **feat(tile)**: Centralized Start/Stop logic shared between App button and Quick Tile via `PanelPreferences.toggleService()`.
- **feat(ui)**: App status now updates live when toggled from the Quick Tile while the app is open.
- **feat(appearance)**: Replaced double-column toggle with a multi-column selector dropdown (1–3 columns).
- **feat(settings)**: Added Backup & Restore — export/import all settings as JSON to/from Downloads folder.
- **feat(ui)**: App-wide Light / Dark / System theme mode support.
- **feat(shizuku)**: Integrated Shizuku for rootless system automation; added Revoke permission option.
- **feat(handle)**: Drag-to-reposition the edge handle directly on screen.
- **feat(interaction)**: Toggle to disable auto-showing keyboard when opening the app picker dashboard.
- **feat(notifications)**: Permission check and direct OEM intent fallback for Notification Access.
- **fix**: Apps no longer vanish on long-press when Drag-to-Split is disabled.
- **fix**: Active notification apps now reliably appear in the panel.
- **fix**: Settings reset fully clears panel apps and hides disabled features from search.
- **fix**: Reversed back arrow in the Support screen corrected.
- **fix**: Disabled panel bounce animation for a smoother feel.

---

### v1.3.1
- **feat(shortcuts)**: Added **Quick Action Shortcut** (Toggle Sidebar) accessible via app icon long-press and launcher widgets.
- **feat(gestures)**: Added **Advanced Tap Gestures** support (Double Tap and Triple Tap) with a smart gesture detection engine and redesigned settings UI.
- **feat(productivity)**: Added **Notification Hub** to the dashboard. View active notifications and launch apps in **Freeform Windows** instantly.
- **feat(ui)**: Completely revamped **Color Picker** with modern skydoves implementation. Added **Hex input**, live preview, and Alpha/Brightness sliders.
- **feat(ui)**: Added **Icon Scale** customization (0.8x to 2.0x). Enlarged icons everywhere while keeping the panel slim.
- **feat(tools)**: Integrated **Volume and Brightness** system controls with continuous hold-to-adjust support and real-time status indicators.
- **feat(handle)**: Significantly expanded **Vertical Offset** range (+/- 500dp) for extreme top/bottom placement.
- **feat(onboarding)**: Modernized **Setup Screen** with Material 3 theme and integrated Notification Access guide.
- **fix**: Fixed bug where disabling swipe hid the pill handle when tap gestures were active.
- **fix**: Optimized panel width calculation to prevent layout bloating when scaling icons.
- **fix**: Fixed various stability issues and crashes related to background service context.

---

### v1.3.0
- **feat(ui)**: Redesigned App Picker UI with a modern dashboard header and staggered entry animations.
- **feat(ui)**: Enhanced 2-column mode with tighter panel width and 10% larger icons.
- **feat(ui)**: Redesigned search result highlights with a satisfying 3D bounce and prominent pill flash.
- **fix(ui)**: Fixed Settings search bar layout bug that caused typed text to be invisible.
- **chore**: Adjusted default picker gap spacing (20dp) for a cleaner layout.

---

### v1.2.3
- **feat(handle)**: Added "Touch Area Width" customization (16dp to 100dp) in Handle & Pill settings.
- **fix(gesture)**: Improved swipe-to-open gesture reliability by reducing sensitivity to backward movement and jitter.
- **fix(handle)**: Fixed "ghosting" or duplicate pills when adjusting settings (enforced aggressive removeViewImmediate).
- **fix(handle)**: Fixed issue where the pill would disappear if gestures were disabled while tap-to-open was still active.
- **fix(layout)**: Synchronized visual pill alignment with dynamic touch area widths.
- **refactor**: Optimized gesture timing and hold duration for a more responsive feel.

---

### v1.2.2
- **feat(handle)**: Introduced Pill Color and Pill Thickness customization for the side handle.
- **feat(layout)**: Improved sidebar and app picker compactness with dynamic height wrapping and capped limits.
- **feat(ux)**: Added 'Restore Default' buttons for all customization sliders across all settings.
- **fix(accessibility)**: Robust accessibility service detection using a static status flag (fixes issues on Vivo and high-res devices).
- **fix(stability)**: Enforced strict screen boundaries for the edge handle to prevent it from disappearing on large screens.
- **refactor**: Simplified handle settings by consolidating trigger area and visual appearance controls.
- **design**: Brighter and more balanced default look for the pill indicator.

---

### v1.2.1
- **feat(freeform)**: Implemented Freeform Window multitasking with smart orientation-aware aspect ratios.
- **feat(icons)**: Added Superior Icon Pack support with full appfilter.xml parsing and heuristic mapping.
- **feat(automation)**: New professional System Automation dialog for ADB/Root setup.
- **feat(layout)**: Added Sidebar Max Height customization in Appearance settings.
- **fix(compatibility)**: Improved device compatibility for Android 14+ and optimized service stability.
- **fix(setup)**: Fixed SetupActivity redirect issue on fresh installations.
- **design(ui)**: Enhanced theme visuals for Realme UI and Rich UI (Glow) with custom strokes and gradients.
- **chore(phones)**: Locked icon scaling to 1.0f for phones to maintain perfect visual balance.

---

### v1.2
- **refactor**: Refactored settings into a multi-page dashboard (Appearance, Interaction, Handle, Tools).
- **feat(glide)**: Replaced slow PackageManager icon loading with Glide + custom AppIconModelLoader.
- **feat(cache)**: Added fast in-memory cache for system default icons.
- **fix(touch)**: Improved FloatingPanelService touch interception for reliable outside clicks.
- **design(accessibility)**: Standardized Accessibility Icon to native Android Material Vector.
- **fix(xiaomi)**: Added Xiaomi/MIUI/HyperOS specific intent fallback for accessibility settings.

---

### v1.1
- **fix(permission)**: Implemented programmatic auto-start detection for MIUI and OriginOS (Vivo) to fix false-positive 'granted' status.
- **fix(service)**: Fixed service restart bug where the sidebar would reappear after manual stopping.
- **feat(docs)**: Updated F-Droid metadata with new phone screenshots.
- **feat(licensing)**: Migrated to a 100% Open Source model for **F-Droid** compliance.
- **feat(ux)**: Unlocked all features by default for the FOSS version.
- **design(ui)**: Redesigned the Support page with community-focused visuals and branding.

---

### Previous Milestones
- **fix**: resolve duplicate apps bug and implement customizable picker gap spacing.
- **feat**: implement premium design system, onboarding screen, and UI optimizations.
- **feat**: overhaul settings UI with Material 3 components and optimized layouts.
- **feat**: migrate screenshot tool to native system action via AccessibilityService.
- **feat(project)**: Initial V1 implementation of OriginOS-inspired Side Panel.
- **feat(project)**: Initial project scaffold — Gradle KTS, version catalog, AndroidManifest.
