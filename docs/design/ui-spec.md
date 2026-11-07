# VoiceTrans VI: UI spec (Compose / Material 3)

Applies to plan Task 8 (screens), Task 5 (language settings) and Task 7 (notification). Users read subtitles while someone is talking, often glancing at a phone lying on a table, often in the dark, and often while the app runs for a long time in the background. **The translated line must be readable at a glance, and the app must never look stopped when it is running, or running when it is stopped.**

## Foundations

**Dark first.** The default theme is **Tối** (dark), and Settings offers Tối / Sáng / Theo hệ thống. The background is pure black `#000000`, so the Samsung AMOLED panel turns those pixels off and saves battery during long sessions with the screen on. Cards sit on a navy-tinted near-black, taken from the logo tile, so they stay distinguishable. Text is off-white, not `#FFFFFF`, to avoid glare on black.

**Colors from the logo (`logo.png`).** These were sampled from the image itself: background `#000209`, blue `#0166F6` (the "A" bubble), violet `#5710DC` (the "文" bubble), cyan `#5FF3FC` (the arrow and mic waves) and red `#C60516` (the LIVE badge).

To stay simple, the UI uses **blue as the only action color**. Violet appears only where the logo puts it, on the translated side. Red is only for Stop and errors. Everything else is navy-tinted greys. There are no gradients or glows; cyan and the gradient stay in the logo. `dynamicColor = false`. All values are tokens in `feature/ui/theme/Color.kt`, and components never use raw hex.

| M3 role | Dark (default) | Light | Used for |
|---|---|---|---|
| background | `#000000` | `#FFFFFF` | Screen (pure black for OLED; the logo's `#000209` is visually the same) |
| surface / surfaceContainer | `#0D1324` | `#F3F5FB` | Utterance cards, settings groups, bottom bar (navy tint from the logo tile) |
| onSurface | `#E8ECF5` | `#0B1530` | Translated text, titles |
| onSurfaceVariant | `#9BA6BF` | `#4F5B76` | Source text, partial, timestamps, helper text |
| outlineVariant | `#2A3550` | `#D5DBE8` | Dividers (decorative only) |
| primary / onPrimary | `#7CB0FF` / `#00163D` | `#0057D9` / `#FFFFFF` | Text links, switches, slider, listening dot, source badge `EN`/`ZH` |
| primaryContainer / onPrimaryContainer | **`#0166F6`** / `#FFFFFF` | **`#0166F6`** / `#FFFFFF` | "Bắt đầu nghe" button: the logo blue, exact |
| secondary / onSecondary | `#C4A8FF` / `#1B0647` | `#5710DC` / `#FFFFFF` | Target-language chip in the top bar, the "Bản dịch" side accent. Never an action. |
| error / onError | `#FF8A8A` / `#3A0006` | `#C60516` / `#FFFFFF` | Error text and icons |
| errorContainer (button) | **`#C60516`** / `#FFFFFF` | **`#C60516`** / `#FFFFFF` | "Dừng" button: the logo LIVE red, exact |
| errorContainer (banner) | `#2A0A0E` / `#FFB3B3` | `#FDE7E9` / `#7A0010` | Capture-blocked / stopped / exit-reason banner |

Measured contrast (WCAG):

- Dark: text/card 15.6, muted/card 7.6, primary/card 8.4, secondary/card 9.2, error/card 8.2, white on the `#0166F6` button 4.96, white on the `#C60516` button 6.1
- Light: text 18.0, muted 5.6–6.2, primary 6.2, secondary 8.4, error 6.1

Card vs background (1.14) is a container boundary, not information, so it does not need 3:1. The "Ready" and "Đang nghe" states use the color plus an icon and text, never color alone.

**Meaning of the two hues (matches the logo):** blue = what was heard (source badge, Start), violet = what it becomes (target-language chip). Never use violet for a button.

**Font:** system default (`FontFamily.Default`, which is Samsung One / Roboto). It renders Vietnamese diacritics, CJK and every other target language's script with system fallback, so do not bundle a font.

**App icon and splash** (source: `logo.png`, 1254×1254, RGB, no alpha):
- **Adaptive icon:** background layer is the solid color `#000209`. The foreground is the logo's rounded tile, scaled so the tile fits the 66dp safe zone of the 108dp canvas, so launcher masks never cut the bubbles. Generate it with Android Studio → Image Asset, not by hand.
- **Legacy icon** (`mipmap-*/ic_launcher.png`): the full logo.
- **Monochrome (themed) icon** for One UI and Android 13+ themed icons: the logo has no single-color version. Draw a simple white vector glyph of the speech bubbles "A文" (`res/drawable/ic_launcher_monochrome.xml`). Until then, omit `<monochrome>`; the launcher then shows the full-color icon.
- **The "LIVE" badge is unreadable at 48dp.** That's acceptable on the launcher, but don't use the logo at < 96dp inside the app. At small sizes use the Material `subtitles` icon.
- **Notification small icon:** a white-on-transparent vector (`ic_stat_subtitles`), never the logo, and **not a mic**, because we don't use it. Android masks it to a silhouette.
- **Splash** (`core-splashscreen`): `windowSplashScreenBackground = #000000`, `windowSplashScreenAnimatedIcon` = adaptive foreground, `windowSplashScreenIconBackgroundColor = #000209`.
- **In-app use:** ModelSetup header (96dp) and Settings → About (64dp only if the badge is cropped. Otherwise 96dp).

**Type:** M3 roles only.
- Translated line: `titleLarge`, `FontWeight.Medium` (on dark, SemiBold blooms), `fontSize = subtitleSizeSp.sp` (default 20, range 14–32), lineHeight 1.4×.
- Source line: `bodyMedium`.
- Badge: `labelMedium`.

**Spacing:** 4/8dp grid. Screen gutter is 16dp, card padding 16dp, and cards are 8dp apart. Content max width is **720dp, centered**, so Fold, Tab and landscape layouts don't stretch lines.

**Icons:** Material Symbols Rounded only (`material-icons-extended`), 24dp, outlined style throughout. Never emoji.

**Motion:** subtle. A new card uses `Modifier.animateItem()` (fade + 8dp slide, about 200ms). The listening dot pulses at 1.2s. When `ANIMATOR_DURATION_SCALE == 0` there is no pulse and no item animation.

## Languages

There are two independent settings. Both are in Settings → **Ngôn ngữ**.

### 1. App language (giao diện)

- Options: **Theo hệ thống**, **Tiếng Việt**, **English**. `res/values/` holds Vietnamese (the default) and `res/values-en/` holds English.
- Implement with the Android per-app language API: `AppCompatDelegate.setApplicationLocales()`, `res/xml/locales_config.xml` and `android:localeConfig`. The choice therefore also appears in Samsung Settings → Apps → VoiceTrans VI → Ngôn ngữ ứng dụng, and the system persists it, so DataStore does not hold it.
- Every user-visible string, including notification text and banner copy, comes from string resources. Nothing is hard-coded in Kotlin.

### 2. Translation language (dịch sang)

- "Dịch sang": a searchable list of all `TranslateLanguage.getAllLanguages()`, with names shown in the current app language (`Locale.forLanguageTag(code).getDisplayName(appLocale)`).
  - The pinned top group is Tiếng Việt (default), English, 中文, 日本語, 한국어, ไทย, Bahasa Indonesia. A divider separates it from the rest, which are sorted alphabetically.
  - Each row shows its pack status on the right: "Đã tải" ✓, "Chưa tải · ~30 MB" or a spinner.
- Choosing an undownloaded language starts its download (respecting Wi-Fi-only). The choice becomes active only when its pack is Ready. Until then the old target stays active, and a helper line says so.
- Choosing a new target while a session is running applies to the **next sentence**. Already translated cards keep their text.
- The source stays **EN/ZH (tự nhận diện)**. This is shown as read-only text with helper "Model nhận dạng giọng nói chỉ hỗ trợ tiếng Anh và tiếng Trung".
- If the source equals the target (for example EN speech and English chosen), the card shows only the source text, with no translation line and no "lỗi".
- **"Gói ngôn ngữ đã tải"** lists each downloaded pack with a delete icon button (`contentDescription = "Xoá gói <tên>"`), behind a confirm dialog. EN and ZH packs and the current target cannot be deleted. Their delete button is replaced by the text "Đang dùng".

## Audio source: sound playing on the phone (not the microphone)

The app translates **audio played by other apps on this phone**, such as YouTube, a browser, a video player or a podcast app. It captures that audio with Android's `AudioPlaybackCapture`. It never listens to the room through the microphone.

**What Android requires, and how the UI explains it**
- **"Ghi âm" (`RECORD_AUDIO`):** Android requires this permission for playback capture even though the mic is never used. The permission screen copy: "Android yêu cầu quyền Ghi âm để thu âm thanh đang phát trong máy. App **không** dùng micro và không lưu âm thanh."
- **Screen-capture consent, every session:** Android shows its own "Bắt đầu ghi hoặc truyền?" dialog each time. VoiceTrans can't skip or remember it. Before the **first** dialog only, show a short sheet:
  > Android sẽ hỏi quyền "ghi/truyền màn hình". Hãy chọn **Toàn màn hình** rồi **Bắt đầu**. VoiceTrans chỉ dùng **âm thanh** để dịch, không ghi hình, không lưu, không gửi đi đâu.
  >
  > [Tiếp tục]

  The app requests the entire-screen mode (`createConfigForDefaultDisplay`), because single-app sharing would stop capture when the user switches video apps.
- If the user cancels the system dialog, Live shows "Cần cho phép để nghe âm thanh trong máy" with [Thử lại].
- While running, One UI shows its **screen-sharing indicator** in the status bar. This is expected, and the sheet above mentions it: "Biểu tượng chia sẻ màn hình sẽ hiện trên thanh trạng thái khi đang dịch."

**What can't be captured** (explained in Settings → Giới thiệu → "Vì sao không có phụ đề?")

| Source | Captured? |
|---|---|
| YouTube, browsers, most video players, podcasts, games | ✓ |
| Apps that block capture: many paid streaming/DRM apps, and **Brave browser** (known Brave bug #47011; Chrome, Samsung Internet and Firefox work) | ✗ silence. After 5 s of silence while something is playing, Live shows the banner and the overlay shows one line: "App này không cho phép thu âm thanh — thử app YouTube, Chrome hoặc Samsung Internet". The session keeps running. |
| Phone and VoIP calls (Zalo/Messenger calls) | ✗. Android forbids it. |

**Music and songs (MVP).** An on-device audio classifier labels each second as speech / singing / music.
- **Speech, including speech over background music:** translated normally.
- **Music only, or singing:** **not translated** (lyrics through a speech model give garbage).
  - Live shows one compact row per music stretch: "♪ Nhạc · 0:42", muted, no badge.
  - The floating subtitle shows a single line "♪ Đang phát nhạc" under the last translations.
  - The notification text says "♪ Đang phát nhạc".
- A dedicated **Lời bài hát** mode is planned for phase 2 (`docs/design/lyrics-mode.md`). The MVP UI doesn't mention it.

**Headphones:** capture works the same with speaker, wired or Bluetooth output. The user can keep the sound in headphones and still get subtitles.

## Where translations appear

| Situation | Where the translation shows |
|---|---|
| The app is open | **Live screen**: the full conversation list (see Screens → Live). The floating subtitle is hidden here so text isn't duplicated. |
| Another app is open (video, video call, browser…) | **Floating subtitle (Phụ đề nổi)** over that app, plus the notification. |
| Screen locked or off | **Notification** only. Android does not draw overlays on the lock screen. Everything is in the Live list when the user returns. |

### Floating subtitle (Phụ đề nổi)

A caption box drawn over other apps while a session is Running and VoiceTrans is not in the foreground. It has **two display modes**.

**1. Translating mode (Đang dịch)** is the normal state. The focus is on the text.
```
     good morning everyone            ← "Hiện câu gốc": 0.8× size, #C8D0E0
     Chào buổi sáng mọi người         ← translation: #FFFFFF, Medium, text shadow
     Hôm nay chúng ta họp về dự án    ← newest at the bottom
   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  ← background = #000000 at the configured
                                         "Độ đậm nền khi dịch" (default 30%; 0% = text only)
```
- The background is drawn **only at the configured opacity**, with no border and no handle. At 0% only the text and its shadow are visible.
- The text always has a shadow (`Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 4f)`), so it stays readable at 0% over a bright video, like TV captions.
- With no new sentence for *X* s ("Tự ẩn khi im lặng", default 8 s), **the whole box including the text fades out**. It fades back in on the next sentence.

**2. Setup mode (Chỉnh phụ đề)** is shown while the user is configuring, so the box's real size and position are obvious.
```
   ┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓  ← solid background #000000 at 85%
   ┃            ═══  (drag handle)    ┃     + 1dp #7CB0FF outline (primary)
   ┃ Chào buổi sáng mọi người         ┃  ← sample text if no real sentence yet
   ┃ Hôm nay chúng ta họp về dự án    ┃
   ┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
     "Kéo để di chuyển"                 ← helper chip below the box
```
- **When it's active:**
  - while Settings → **Phụ đề nổi** is open. The real overlay is shown on top of the app, at its real position, even though the app is in the foreground. It is not an in-app mock.
  - while the user is dragging the box.
  - for 2 s after any overlay setting changes.
- **Auto-hide is suspended** in setup mode.
- **Leaving it:** when the user leaves the Phụ đề nổi screen, stops dragging, or 2 s pass after the last change, the box animates (300ms) back to translating mode at the configured opacity. If the app is in the foreground, it hides.
- **Without the overlay permission,** the Phụ đề nổi screen shows an in-app preview card (same rendering, setup mode) instead.

**Shared rules**
- **Colors are fixed, not themed** (black background, white text), because the box sits over arbitrary content.
- **Content:** the newest *N* translated sentences (1–3, default 2). The newest is at the bottom, and older ones fade to 70% alpha. Partial text is never shown, so the box doesn't flicker.
- **Position:** Trên / Giữa / Dưới presets (default Dưới), or drag. See "Orientation & responsive" for how position, width and height adapt to portrait and landscape.
- **Touch (unlocked).** The window is the size of the box only (`WRAP_CONTENT`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`), so touches outside the box go to the app underneath.

  | Gesture | Result |
  |---|---|
  | **Drag** (move beyond touch slop) | Moves the box vertically, at any time during a session. It switches to setup mode while dragging, and the position is saved on release. |
  | **Long-press (500ms, no movement)** | Haptic, then a control row appears under the box for 5 s: **[✕ Đóng phụ đề] [⏻ Thoát app]** (48dp icon+label buttons on a `#000000` 85% strip) |
  | Tap | Nothing, so an accidental touch while watching video does nothing |

  - **✕ Đóng phụ đề** closes the floating window for the rest of this session. **Translation keeps running**, and the Live list and notification keep updating. The notification shows a "Hiện phụ đề" action to bring the box back.
  - **⏻ Thoát app** shuts everything down:
    - stops the session and the foreground service;
    - removes the overlay and cancels all notifications;
    - closes the app's tasks (`ActivityManager.appTasks.forEach { it.finishAndRemoveTask() }`);
    - releases the native ASR/VAD models and ML Kit clients, so the memory is freed.

    It shows no confirmation dialog: the long-press is already deliberate. The next launch starts fresh, from the launcher or the Quick Settings tile.
- **Khoá phụ đề (locked):** the box is fully click-through (`FLAG_NOT_TOUCHABLE`), so it can't be dragged or long-pressed. Android only passes touches through an overlay whose **window alpha ≤ 0.8**, so the window alpha is capped at 0.8, and the settings helper text says so. To unlock quickly, the notification shows a **"Mở khoá phụ đề"** action while locked. It can also be unlocked from Settings.
- **Live changes:** every "Phụ đề nổi" setting (opacity, size, lines, position preset, lock) applies to the visible box immediately during a session. Nothing requires a restart.
- **What the overlay can and cannot cover.** It is a `TYPE_APPLICATION_OVERLAY` window: above every normal app, but below what Android reserves.

  | Shown over | Not shown / covered |
  |---|---|
  | Any app, full-screen video, video calls, PiP windows, the home screen | The lock screen (notification only) |
  | | The status bar, notification shade and keyboard, which draw on top of it |
  | | System-protected screens where Android hides all overlays: permission dialogs, the package installer, some Settings pages, and apps that opt in to `setHideOverlayWindows(true)` (many banking/security apps) |

  When it's hidden this way nothing is lost. The sentences are in the Live list and the notification.
- **Permission:** "Hiển thị trên ứng dụng khác" (`SYSTEM_ALERT_WINDOW`, granted by the user in `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`). Without it, the floating subtitle is simply off, the notification still works, and Settings shows the status with a "Cấp quyền" button.
- **Android 17 note:** the overlay is drawn *by* the already-running `mediaProjection` FGS. The overlay must never be used as a reason to start the service from the background.
- **TalkBack:** the box is `importantForAccessibility = no`. The Live list and the notification already carry the text, and an overlay announcing every sentence would be noise.

## Starting and stopping

**Start** (must come from a visible UI, because of the Android 17 WIU rule):
- The "Bắt đầu nghe" button in Live.
- The Quick Settings tile **"VoiceTrans"** when stopped: the tap opens Live with `autoStart = true`, and Live starts the session as soon as it is visible.
- The "Phiên dịch đã dừng — chạm để tiếp tục" notification, which opens Live.

**Stop** (allowed from anywhere):

| Where | How |
|---|---|
| Live screen | "Dừng" button |
| Notification | "Dừng" action |
| Floating subtitle | Long-press → **Đóng phụ đề** (closes the window only; translation continues) or **Thoát app** (stops everything and closes the app) (when unlocked) |
| Quick Settings tile | Tap while active (the tile shows active/inactive state and the subtitle "Đang dịch") |
| Automatic | **No speech for N minutes** (setting "Tự dừng khi không có ai nói": Không / 10 / 30 / 60 phút, default **30**). Shows the dismissible notification "Đã tự dừng sau 30 phút im lặng — chạm để tiếp tục". |
| System status bar | The **screen-sharing chip** → "Dừng chia sẻ". The session ends as `PROJECTION_ENDED`. |
| System | Audio error, or killed. This shows the banner and notification described elsewhere. |

All stop paths end in the same `TranslationSession.stop()`, and the tile, notification and overlay update from `SessionState`.

## Background running

The app is meant to keep translating with the screen off or while another app is open. The UI must make this obvious and trustworthy.

- **Background setup step** (first run, after ModelSetup, before Live). It is one screen titled "Cho phép chạy nền" with three rows. Each row shows status (icon + text) and an action:
  1. Thông báo: "Cần để hiển thị phụ đề khi app chạy nền" → requests `POST_NOTIFICATIONS`.
  2. Pin: "Để Samsung không tắt app khi màn hình tắt" → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The helper text adds "Ngoài ra: Cài đặt → Ứng dụng → VoiceTrans VI → Pin → Không hạn chế".
  3. Phụ đề nổi: "Để xem bản dịch khi đang dùng app khác" → `ACTION_MANAGE_OVERLAY_PERMISSION`. This row is optional and labelled "(tuỳ chọn)".

  The "Tiếp tục" button is always enabled, because users can't be forced. If either row is not granted, Live shows a dismissible warning card that links back to this screen.
- **Live status row while Running:** "● Đang nghe âm thanh trong máy · mở video bất kỳ". When stopped it shows "○ Đã dừng".
- **Ongoing notification** (channel "Phiên dịch", `IMPORTANCE_LOW`, so it is silent and makes no heads-up popups):
  - Title: "Đang dịch EN/ZH → <tên ngôn ngữ đích>".
  - Text: `BigTextStyle` with the last source line (muted) and the last translated line. It is updated per finished translation, with `setOnlyAlertOnce(true)`.
  - Actions (max 3): **Dừng**; **Ẩn phụ đề / Hiện phụ đề** (toggles the overlay for this session; only when the overlay is enabled and permitted); **Mở khoá phụ đề** (only while locked). Tapping the body opens Live. All use `FLAG_IMMUTABLE`.
  - On a system stop (sharing stopped from the status bar, idle timeout, or killed): replace the ongoing notification with a dismissible one, "Phiên dịch đã dừng — chạm để tiếp tục". Tapping it opens Live, and the user taps Start. This preserves the Android 17 WIU rule.
- The Live list is owned by the session, not the screen. Leaving the app and coming back shows everything translated while away, scrolled to the end.

## Orientation & responsive

The main use case is **a full-screen landscape video with the floating subtitle on top**, so landscape is a first-class layout, not a fallback. The app never locks orientation. API 37 ignores locks on large screens anyway, and the user rotates for video.

### Floating subtitle on rotation
- **The box re-lays out on every configuration change** (rotation, fold/unfold, split-screen). The controller listens to `onConfigurationChanged` and recomputes width and y with `updateViewLayout`, without re-adding the view and without flicker.
- **Safe area:** width and y are computed inside the window's **safe rect** = screen minus `systemBars ∪ displayCutout` insets (`WindowMetrics.windowInsets.getInsetsIgnoringVisibility`). In landscape this keeps the box clear of the camera cutout and the side 3-button nav bar. When a video hides the system bars (immersive), the insets "ignoring visibility" are still used, so the box doesn't jump when the bars reappear.
- **Width:** `min(safeWidth − 32dp, 720dp)`, centered in the safe rect. On a landscape phone, about 720dp of about 850dp.
- **Height cap: the box is never taller than 35% of the safe height.** In landscape on a phone (about 360dp tall), that is about 126dp, roughly two lines at 18sp. If the configured lines, `Hiện câu gốc` or a 200% font scale would exceed it, the **oldest lines are dropped first**, and the newest translation is never cut. Text never shrinks automatically, because the user chose the size.
- **Position is remembered separately for portrait and landscape.** It is stored as a fraction (0 = top of the safe area, 1 = bottom), not pixels. Dragging in landscape doesn't move the portrait position, and the reverse also holds. Choosing a preset (Trên/Giữa/Dưới) clears **both** custom positions.
  - Presets: Dưới = 24dp above the safe bottom in portrait, and **16dp** in landscape (video controls usually sit at the very bottom, and the user can drag above them). Giữa = centered. Trên = 24dp below the safe top.
- **Setup mode** shows the real box in the current orientation. Rotating while on Settings → Phụ đề nổi shows the landscape position live.

### App screens
Use `WindowSizeClass` (compact / medium / expanded) for **height** as well as width:

| Screen | Portrait / normal height | **Compact height** (landscape phone, < 480dp tall) |
|---|---|---|
| Live | TopAppBar + status row + list + 64dp bottom Start/Stop bar | Small TopAppBar with the status as its subtitle line. **Start/Stop becomes an extended FAB at bottom-end** (56dp), and the list gets the full height. Banners collapse to one line with a "Chi tiết" expander. |
| Settings, sub-screens | Scrolling list | Same, with content max width 720dp, centered |
| ModelSetup, BackgroundSetup, consent explainer sheet | Column | **Must scroll** (`verticalScroll` / scrollable sheet). The primary button stays reachable: it is pinned in a bottom bar, or sits at the end of the scrolled content, never off-screen. |
| Language picker | Search field + list | Same. The search field stays pinned at the top. |

- **Content max width 720dp, centered,** on every screen at every width (Fold inner screen, Tab, landscape). There is no two-pane layout in the MVP.
- **The side nav bar and cutout** are handled by `WindowInsets.safeDrawing` padding on every screen, plus `displayCutout` in landscape.

### State that must survive rotation, fold and resize
- The Live list scroll position (`rememberLazyListState` is saveable), plus the "↓ Câu mới" chip state.
- Open dialogs and sheets: delete-pack confirm, consent explainer, target-language search text (`rememberSaveable`).
- **A capture-consent request in flight:** rotating while Android's "Bắt đầu ghi hoặc truyền" dialog is open must still deliver the result and start the session. Register `CaptureConsent` unconditionally in `onCreate`, so the Activity Result registry redelivers it to the recreated activity.
- The overlay's control row (long-press) simply closes on rotation. It's transient.
- The session itself is untouched, because it lives in the service.

## Screens

### Live (main)

```
┌ TopAppBar: "VoiceTrans VI"      [EN/ZH → VI ▾] [⚙] ┐  ← chip opens the target picker
│ ● Đang nghe âm thanh trong máy · mở video bất kỳ    │
│ ┌ errorContainer banner (BLOCKED_BY_APP / stopped / │
│ │ / exit reason) + [Bắt đầu lại]                     │
│ └────────────────────────────────────────────────────┘
│ ┌ warning card: background permission missing [Sửa] ┐ (dismissible)
│ LazyColumn (key = utterance.id)                      │
│ ┌ card #0D1324 ──────────────────────────────────┐   │
│ │ [EN] 10:42                         muted labels │   │
│ │ good morning everyone              bodyMedium   │   │
│ │ Chào buổi sáng mọi người           subtitle size│   │
│ └────────────────────────────────────────────────┘   │
│   translating: "Đang dịch…" muted placeholder line   │
│   failed: ⚠ "(lỗi dịch)" in error color             │
│ partial (no card, italic, muted): "hel…"             │
│                          [ ↓ Câu mới ] if scrolled up│
├ bottom bar surfaceContainer + navigationBars insets ┤
│ [ ▶  Bắt đầu nghe ]   primaryContainer #0166F6, 64dp │
│ [ ■  Dừng ]           #C60516 (logo red) while Running│
└──────────────────────────────────────────────────────┘
```

- **Auto-scroll only when the user is already at the bottom.** Otherwise show the "↓ Câu mới" chip.
- The badge is text (`EN` / `ZH`) in a tonal chip, never color only.
- **Empty state:** speaker/video icon plus "Bấm **Bắt đầu nghe**, rồi mở video tiếng Anh hoặc tiếng Trung trên điện thoại. Phụ đề dịch sẽ hiện ngay trên video."
- The Start/Stop button is disabled with a spinner between the tap and `Running`. A haptic fires on toggle.
- **TalkBack:** each card merges into "<Tên nguồn>: <source>. Bản dịch: <translation>". One hidden `liveRegion = Polite` node announces only the newest finished translation, never partials.

### ModelSetup (gate before Live, first run or when a required pack is missing)

- Explanation: "Cần tải gói dịch một lần (~90 MB, nên dùng Wi-Fi). Sau đó app chạy hoàn toàn offline."
- The required packs are always English, 中文 and the current target. Each row has an icon and status text: "Chưa tải" / indeterminate progress + "Đang tải…" / ✓ "Sẵn sàng" / ⚠ error. A fixed row says Nhận dạng giọng nói ✓ "Có sẵn".
- Primary button "Tải gói dịch" ("Thử lại" after an error). It shows Wi-Fi helper text when waiting for Wi-Fi.

### Settings (grouped, each section with a header)

1. **Ngôn ngữ:**
   - Ngôn ngữ ứng dụng
   - Dịch sang
   - Ngôn ngữ nguồn (read-only)
   - Gói ngôn ngữ đã tải
2. **Hiển thị trong app:**
   - Giao diện: Tối / Sáng / Theo hệ thống (segmented buttons)
   - Cỡ chữ phụ đề: slider 14–32 with a live preview card
3. **Phụ đề nổi.** This is a row that opens its own screen. While that screen is open, the real overlay is shown in **setup mode** (solid background + outline + drag handle) at its real position, and it reflects every change live. Without the permission, an in-app preview card is shown instead.
   - Bật phụ đề nổi: switch, default **on** (when permitted), with the permission status and a [Cấp quyền] button.
   - **Độ đậm nền khi dịch:** slider 0–100%, step 10, default **30%**. Label "30% (nền mờ)" / "0% (chỉ chữ)" / "100% (nền đặc)". Helper: "Khi chỉnh cài đặt, nền luôn hiện rõ để bạn thấy kích thước; khi dịch, nền dùng độ đậm này". While dragging this slider, the box shows the chosen opacity (not setup opacity), so the user sees the real result. The outline stays visible.
   - Cỡ chữ: 14–28, default 18.
   - Số dòng hiển thị: 1 / 2 / 3, default 2.
   - Hiện câu gốc: switch, default off.
   - Vị trí: Trên / Giữa / Dưới (default Dưới), plus "Đặt lại vị trí". Helper: "Hoặc kéo phụ đề trực tiếp — vị trí dọc và ngang được nhớ riêng".
   - Tự ẩn khi im lặng: Không ẩn / 5 / 8 / 15 s, default 8 s.
   - Khoá phụ đề (bấm xuyên qua): default off. Helper: "Phụ đề không nhận chạm (không kéo, không nhấn giữ được); độ đậm tối đa 80% theo giới hạn của Android. Mở khoá từ thông báo."
   - Every control except the master switch is disabled (0.38 alpha, with the reason in helper text) while the master switch is off or the permission is missing.
4. **Chạy nền:**
   - Thông báo: status + action
   - Pin: status "Không hạn chế" ✓ / "Đang bị tối ưu" ⚠ + action
   - Tự dừng khi không có ai nói: Không / 10 / 30 / 60 phút (default 30)
   - "Mở lại hướng dẫn chạy nền"
5. **Tải xuống:** Chỉ tải qua Wi-Fi (switch).
6. **Giới thiệu:** app version, model versions, and "Vì sao không có phụ đề?" (the capture table from "Audio source").

## Acceptance checks (in addition to the plan's tests)

- All touch targets ≥ 48dp, and every icon button has a `contentDescription` from string resources.
- Switch the app language to English: every screen, the notification and the dialogs show English, with no Vietnamese left in the UI chrome.
- Font scale 200%: no clipped text, and the bottom button stays visible.
- Dark (default) in a dark room at the lowest brightness: no glare, and cards are distinguishable from the background. Light mode is also checked.
- Fold or landscape: content stays ≤ 720dp wide and centered.
- Start a session, press Home, open another app for 5 min, return: the notification updated while away, and the list shows everything, scrolled to the end.
- Floating subtitle: start a session, open YouTube with a bright video, and speak. The box appears at the bottom at 60% background, and the text is readable. Set opacity to 0%: the text is still readable thanks to the shadow. Lock it: taps on the video's play button under the box work.
- Drag the box to the top in portrait. Rotate to landscape: the box uses the landscape position (default Dưới), not the portrait one. Drag it in landscape, then rotate back: the portrait position is unchanged.
- Landscape full-screen YouTube with 3 lines, Hiện câu gốc on and font scale 200%: the box stays ≤ 35% of the height and shows the newest translation. On a phone with a side camera cutout and 3-button nav, nothing is hidden under the cutout or the nav bar.
- Live in landscape: Start/Stop is a FAB, and at least 4 utterance cards are visible on a 6.1" phone.
- Rotate while the system screen-capture dialog is open, then tap Bắt đầu: the session starts.
- While YouTube plays: long-press-drag the box to the top. Change opacity in Settings from the notification shortcut and see it apply without stopping. Long-press the box → Đóng phụ đề: the box disappears, and the notification keeps updating. Then "Hiện phụ đề" brings it back. Long-press → Thoát app: the notification, overlay and tile all turn off, the app leaves Recents, and `adb shell dumpsys activity services <pkg>` shows no service.
- Lock the box, then use "Mở khoá phụ đề" from the notification: the box becomes draggable again.
- Start from the Quick Settings tile with the app closed: Live opens and starts listening. The tile shows active.
- Open Settings → Phụ đề nổi: the real overlay appears with a solid background and an outline at its real position. Drag it: it moves. Go back: it returns to 30% and hides because the app is in the foreground. Press Home during a session: the box shows at 30% with focus on the text.
- A single accidental tap on the box while a video plays does nothing.
- Play a YouTube EN video with headphones: subtitles appear on the overlay, and the status bar shows the sharing indicator but no green mic dot.
- Play Netflix (or another DRM app): within 5 s the overlay and Live show the "không cho phép thu âm thanh" message. Switch to YouTube: subtitles resume without restarting.
- Stop from the status-bar sharing chip: the session ends, and the notification offers "chạm để tiếp tục".
