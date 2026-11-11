# Project History & Milestones 📜

**Project Persona**: Smart Edge: Sidebar & Gestures — Floating overlay service launcher
**Tech Stack**: Kotlin, Android SDK 26+, Material3, ViewBinding, DynamicAnimation, Glide
**Design Language**: Material You (M3) + Custom Glassmorphism

---

### Unreleased
- **fix(audit)**: Full-codebase audit (110 findings) fixed: `AppPickerActivity` added to the manifest (it crashed Settings search with `ActivityNotFoundException`); static-shortcut `xmlResId` no longer overwritten by the next activity; root probe reads after the bounded wait; volume read-back + shell off the main thread; indicator moved to its own overlay window (it was invisible after `closePanel` detached the panel); brightness prompt throttled; export/import now round-trips the split percent + language; import clamps/validates every value (sliders no longer crash on a bad backup); dead `SettingsActivity` (546 lines), `saveEditingItem()`, and `getAllActivities()` deleted; `getAppsForIdentifiers()` restored after an accidental deletion in the same cleanup.
- **fix(audit)**: Panel gestures and layout: drag buttons track the finger (±60dp) and spring back; drag remainder no longer lost; per-button accumulators; separator drag uses the split budget; black-screen restores the float brightness; scroll positions survive column changes and recent-list refreshes; tool rows use the measured 40dp (not 76dp) and the close button 40dp (not 48dp); recent-list de-dup no longer depends on submitList call order; edge handle has one height formula (1.5×) and one visibility policy (`shouldShowHandle()`); black-screen overlay failure tears the session down instead of leaving the device dimmed.
- **fix(audit)**: Data layer and picker: picker cache invalidation now clears the activities sub-cache; `getAllApps()` degrades to empty on a PackageManager failure instead of crashing; both TTLs use `uptimeMillis()`; label backfill is one-shot per process with batched writes; custom-item ids are validated + deduped on read (covers import) and `updateCustomItem()` reports failure; reordering custom items splices them into their existing sidebar slots instead of moving them to the end; label writes hold the panel-list lock; cache hits refresh `appearanceKey`; all three picker lists sync in-panel state on copies; tree headers show the filtered count; app labels are carried in `PickerData` (no more main-thread PackageManager calls per header); Turkish-locale `lowercase()` fixed in scheme validation and OEM detection; the dead `getAutoScalingFactor()` stub and the duplicated MIUI/Vivo reflection removed.
- **fix(audit)**: Settings and setup: search "jump to setting" now works for all six sections; the text-action editor validates before dismissing and new actions default to enabled; the Tools master switch actually disables its sub-switches; bottom-sheet results survive rotation via `setFragmentResult`; the secure-settings sheet runs shell commands off the main thread and dismisses once a grant lands; the Origin-theme accent lock is enforced on the switch; setup no longer fakes "auto-start granted" on undetectable OEMs; notification access is optional (not in "All granted" or the Grant-All chain); "Grant All" chains through the missing permissions; the sideload note hides again after granting overlay; dynamic shortcuts no longer wipe other shortcuts; the icon-pack list loads off the main thread.
- **fix(audit)**: Build, resources, and tests: ProGuard blanket `-keep` narrowed to real targets (manifest components are kept by R8's defaults; reflection only touches framework classes) and the false Crashlytics justification removed (log stripping kept); the three mirrored test suites rewritten to call real production code (`ToolsLayout` extracted with the 7th tool and 128 combinations, `CustomItemValidation` with a Turkish-locale case, `CustomItemJson` with duplicate/non-UUID cases, `MediaVolumeMath` extracted with its own suite); the float brightness round-trip is now asserted; the split small-budget branch, accumulator scale change, and huge step count are covered; `release.yml` falls back to the Unreleased section (and fails) when a tag has no changelog header, with literal tag matching; the test job is a shared reusable workflow with read-only permissions and Node-24 actions; the Gradle wrapper uses `-bin`; `lint abortOnError` is now `true`; the dead `androidTestImplementation` deps, unused `Theme.SidePanel.AppPicker`, 19 unused colours, and the UTF-16LE-corrupted `.gitignore` tail are gone; `app/release.keystore` is gitignored.
- **fix(sidebar)**: Volume ticks now move exactly one hardware media step on every phone: Smart-Edge sets the absolute volume index (`setStreamVolume`, via the new shared `MediaVolumeApplier`) instead of relative `adjustStreamVolume`, because one adjust call moves ~10 indices (6%) on the OnePlus 13 — one physical volume press jumps 6% too — while an absolute set moves exactly one index (proven by the probe). Falls back to the privileged shell absolute set, then to relative adjust, if the absolute set is rejected.
- **feat(sidebar)**: One volume tick is now exactly one hardware media-volume step on every phone, in every path (edge slide, tools long-press drag, and drag-to-adjust buttons). The drag distance per tick adapts to the phone's own media step count, read live from `getStreamMaxVolume(STREAM_MUSIC)`, so a 160-step phone moves 0.625% per tick and a 15-step phone moves one 6.67% step per tick, while the drag distance for the full range stays the same. This replaces the 1% volume accumulation: volume no longer waits for sub-step percentages to add up, it moves a whole step on every tick.
- **feat(sidebar)**: Brightness changes are now 1% per step in every path (edge slide and long-press drag on the brightness tools). Previously a brightness step was 1/255 (~0.4%); sub-step percentages now accumulate until they make a whole level, so the applied change averages exactly 1% per step.
- **test**: Extracted the 1% step logic into `PercentStepAccumulator` and the split math into `AppsRecentSplit`, both pure JVM units, and added `PercentStepAccumulatorTest` (accumulation, sign changes, refund of unapplied steps); the split tests now call the real implementation instead of a mirrored formula. The volume step matching is covered by `VolumeStepMathTest` (distance per step at 15/100/160 steps, full-range travel invariant). All 148 unit tests pass.
- **ci**: GitHub Actions now gates the release build on unit tests — `release.yml` runs `:app:testDebugUnitTest` in a `test` job first and the release `build` job `needs: test`, so a release APK is never built or published when tests fail; new `ci.yml` runs the same tests on every push and pull request to `master`.
- **fix(build)**: All 14 compiler warnings eliminated by fixing the underlying problems, not suppressing them: deprecated `FLAG_FULLSCREEN` removed (layout flags already cover the area), deprecated `SCREEN_BRIGHT_WAKE_LOCK` removed (the black-screen overlay's `FLAG_KEEP_SCREEN_ON` is its supported replacement), deprecated `systemUiVisibility`/`setOnSystemUiVisibilityChangeListener` replaced with the platform `WindowInsetsController` on API 30+ (legacy flags below 30), deprecated `AccessibilityNodeInfo.recycle()` removed (the platform manages node lifetime), shadowed `isLandscape` and ambiguous `return@forEach` labels fixed, unused `ci` variable and dead `sizeDp` parameters removed, and always-true `is ImageView` check and unnecessary safe call cleaned up.
- **feat(sidebar)**: Draggable separator between the sidebar app list and the Recent list — drag it to adjust each list's share of their combined height, default 50% / 50%, persisted in `apps_recent_split_percent` (clamped 20–80%).
- **fix(sidebar)**: Recent list now excludes apps already pinned in the sidebar (by package, including pinned activities/shortcuts), filtering before the recent-count limit so the section still fills up; also guarded in `SidePanelView.setRecentApps`.
- **feat(sidebar)**: Opening the app picker (expand) now switches the app list, Recent list, and tools grid all to single-column mode together (Recents was previously left in two-column mode), and each list returns to its previous scroll position when the picker closes and two-column mode returns. The old forced `scrollToBottom()` on picker open is removed.
- **fix(core)**: `closePanel()` no longer leaks the full-screen overlay when the panel view failed to initialise; `onDestroy()` now restores brightness if Black Screen was active.
- **fix(build)**: A fresh checkout without `keystore.properties` no longer fails Gradle configuration — the release `signingConfig` used `getByName("release")`, which threw even for `assembleDebug`; it now uses `findByName` so release builds are simply unsigned when no keystore is present.
- **fix(perf)**: Re-enabled RecyclerView view recycling on the sidebar and picker lists (modest cache/pool); picker search is debounced 200ms; `getPickerData()` now honours its 60s TTL and serialises concurrent loads; `getAppsForIdentifiers()` no longer re-parses JSON / re-reads prefs per identifier; `recordLaunch()` persists history + debounce state in one atomic prefs edit; panel-list read-modify-write is serialised on a shared lock.
- **fix(core)**: `AutomationManager` process execution now has a 10s timeout with stream cleanup, and `refresh()` is throttled (5s) and single-flight; Content Picker tree walks are coalesced and the callback is re-checked at post time; notification-listener disconnect/destroy clears stale packages and listeners are posted to the main thread; EdgeHandle IME animators are now a single cancellable animator; confirm-overlay delayed removal captures its own label.
- **test**: Added `AppsRecentSplitAndRecentsTest` covering the 50/50 split defaults/limits, split-height math, and recent-exclusion by package.

### v1.3.7 (Latest)
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
- **fix(multitasking)**: Fixed freeform window feature to correctly spawn floating windows with system caption bars and better orientation detection.
- **fix(multitasking)**: Restored the missing Multitasking & Windowing settings section (Freeform and Split-screen toggles) to the dashboard.
- **fix(ui)**: Fixed invisible pill graphic issue by using correct system defaults for reset actions and forcing synchronous redraws.
- **feat(automation)**: Improved Native Gesture Engine dialog feedback to accurately and instantly reflect Root/Shizuku active connection states.
- **fix(multitasking)**: Smoothed out freeform window launches by switching to native system transitions instead of custom fade animations.

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
