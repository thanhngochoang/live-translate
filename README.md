# VoiceTrans VI — Dịch thoại offline sang tiếng Việt (Android)

App Android nghe giọng nói **tiếng Anh hoặc tiếng Trung (Quan thoại)**, chuyển thành văn bản và **tự động dịch sang tiếng Việt**. Sau lần tải model đầu tiên, toàn bộ xử lý chạy **offline trên thiết bị**.

> Tài liệu này là spec để bắt đầu implement. Đọc hết mục **Quyết định đã chốt** và **Câu hỏi mở** trước khi code.

### Quyết định đã chốt
- **Thiết bị:** Samsung Android (có Google Play Services), nên dùng **ML Kit** làm engine dịch. Fallback ONNX không cần cho MVP.
- **Phân phối:** app **local**, cài trực tiếp qua APK (sideload/adb). **Chưa lên Google Play**, nên không bị giới hạn 200 MB của base APK và không cần Play Asset Delivery.

---

## 1. Mục tiêu & phạm vi

### Trong phạm vi (MVP)
- Nghe liên tục từ micro, tự cắt câu (VAD).
- Nhận dạng giọng nói EN + ZH, kể cả câu trộn Anh–Trung.
- Tự nhận biết ngôn ngữ nguồn của từng câu.
- Dịch từng câu sang tiếng Việt và hiển thị phụ đề kiểu hội thoại (gốc + bản dịch).
- Chạy nền liên tục bằng Foreground Service.
- Tải model lần đầu và quản lý model (kiểm tra, tải lại, xoá).

### Ngoài phạm vi (để sau)
- Dịch chiều VI → EN/ZH.
- Đọc bản dịch bằng giọng nói (TTS) — thiết kế sẵn interface, implement ở phase 2.
- Tiếng Quảng Đông, các phương ngữ khác.
- Lưu lịch sử lên cloud, đồng bộ tài khoản.

### Mục tiêu phi chức năng
| Tiêu chí | Mục tiêu |
|---|---|
| Hoạt động offline | 100% sau khi đã tải model |
| Độ trễ cuối câu → hiện bản dịch | < 1 giây trên máy tầm trung (Snapdragon 7xx / Dimensity 7xxx) |
| Tổng dung lượng model | ≤ 150 MB |
| Min SDK | 26 (Android 8.0) |
| Target SDK | Bản mới nhất được Play Store yêu cầu |
| Quyền riêng tư | Audio không bao giờ rời thiết bị |

---

## 2. Kiến trúc tổng quan

```
┌─────────────┐   PCM 16kHz mono   ┌──────────────┐   speech segments
│ AudioRecord │ ─────────────────▶ │ Silero VAD   │ ─────────────────┐
└─────────────┘                    └──────────────┘                  │
                                                                     ▼
                                              ┌────────────────────────────────┐
                                              │ Streaming Zipformer zh-en (ASR)│
                                              │  partial text ─▶ UI (xám)      │
                                              │  final text   ─▶ pipeline      │
                                              └────────────────────────────────┘
                                                                     │ final text
                                                                     ▼
                                              ┌────────────────────────────────┐
                                              │ LanguageDetector               │
                                              │  có ký tự CJK  → ZH            │
                                              │  ngược lại     → EN            │
                                              └────────────────────────────────┘
                                                                     │
                                                                     ▼
                                              ┌────────────────────────────────┐
                                              │ Translator (interface)         │
                                              │  impl A: ML Kit (mặc định)     │
                                              │  impl B: OPUS-MT ONNX (no-GMS) │
                                              └────────────────────────────────┘
                                                                     │ vi text
                                                                     ▼
                                              ┌────────────────────────────────┐
                                              │ UI phụ đề  (+ TTS ở phase 2)   │
                                              └────────────────────────────────┘
```

### Nguyên tắc
- **Chỉ dịch kết quả final** (khi VAD/ASR báo endpoint). Partial result chỉ để hiển thị, không gửi đi dịch, nhằm tiết kiệm pin và tránh nháy UI.
- Mỗi tầng giao tiếp qua **interface**, để có thể thay engine mà không đụng UI.
- Pipeline chạy trên **coroutine/dispatcher riêng**, không chạy trên main thread.

---

## 3. Tech stack

| Thành phần | Lựa chọn | Ghi chú |
|---|---|---|
| Ngôn ngữ | Kotlin | |
| UI | Jetpack Compose + Material 3 | |
| Kiến trúc | MVVM + Clean-ish (ui / domain / data) | |
| DI | Hilt | |
| Async | Kotlin Coroutines + Flow | |
| VAD + ASR | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Android AAR, Kotlin API) | Dùng prebuilt AAR từ GitHub Releases |
| Model ASR | `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` (bản int8) | Song ngữ zh-en, streaming |
| Model VAD | `silero_vad.onnx` | Có trong release của sherpa-onnx |
| Dịch (mặc định) | Google ML Kit Translation (`com.google.mlkit:translate`) | Cần Google Play Services |
| Dịch (fallback) | Helsinki-NLP `opus-mt-en-vi`, `opus-mt-zh-en` export ONNX int8 + ONNX Runtime Mobile | Cho máy không có GMS |
| TTS (phase 2) | sherpa-onnx + Piper `vi_VN` VITS | |
| Lưu trữ | DataStore (settings), Room (lịch sử, tuỳ chọn) | |

> ⚠️ Kiểm tra lại **phiên bản mới nhất** và **tên file model chính xác** trên trang release của sherpa-onnx và Maven trước khi khai báo dependency. Tên ở trên chỉ để tham chiếu.

---

## 4. Cấu trúc module đề xuất

```
app/                         # Activity, navigation, Hilt setup
core/
  audio/                     # AudioRecorder (AudioRecord wrapper) → Flow<ShortArray>
  model/                     # ModelManager: tải, giải nén, checksum, đường dẫn
feature/
  asr/                       # VadEngine, AsrEngine (sherpa-onnx) → Flow<AsrEvent>
  translate/
    api/                     # interface Translator, LanguageDetector
    mlkit/                   # MlKitTranslator
    onnx/                    # OnnxOpusTranslator (fallback, phase 2)
  tts/                       # interface Speaker (phase 2)
  session/                   # TranslationSession: ghép pipeline, ForegroundService
  ui/                        # Compose screens: Live, Settings, ModelSetup
```

### Interface chính (tham chiếu)
```kotlin
sealed interface AsrEvent {
    data class Partial(val text: String) : AsrEvent
    data class Final(val text: String, val startMs: Long, val endMs: Long) : AsrEvent
}

enum class SourceLang { EN, ZH }

interface LanguageDetector { fun detect(text: String): SourceLang }

interface Translator {
    suspend fun ensureReady(): Result<Unit>              // tải/kiểm tra model
    suspend fun translate(text: String, from: SourceLang): Result<String>
    fun close()
}

data class Utterance(
    val id: Long,
    val source: String,
    val sourceLang: SourceLang,
    val vietnamese: String?,        // null khi đang dịch
    val timestampMs: Long,
)
```

### Language detection
Dùng heuristic đơn giản và đủ dùng cho đúng 2 ngôn ngữ: nếu tỉ lệ ký tự thuộc khối CJK Unified Ideographs (`\u4E00-\u9FFF`) ≥ 30% thì là ZH, ngược lại là EN. Câu trộn mà ZH chiếm phần lớn thì xử lý theo ZH. ML Kit Translator dịch được câu có lẫn vài từ tiếng Anh.

---

## 5. Quản lý model

| Model | Nguồn | Dung lượng (ước tính) | Cách tải |
|---|---|---|---|
| Silero VAD | sherpa-onnx releases | ~2 MB | Bundle trong `assets/` |
| Zipformer zh-en int8 | sherpa-onnx releases | vài chục MB (kiểm tra thực tế) | **Bundle trong `assets/`** |
| ML Kit en→vi, zh→vi | Google (qua `RemoteModelManager`) | ~30 MB / ngôn ngữ | ML Kit tự tải **một lần** (cần mạng) |

App chỉ cài local nên **bundle thẳng model ASR + VAD vào APK**:
- Đặt file `.onnx` và `tokens.txt` vào `app/src/main/assets/models/...`.
- Thêm `androidResources { noCompress += "onnx" }` để không bị nén, giúp load nhanh.
- sherpa-onnx đọc được model trực tiếp từ `AssetManager`, nên không cần copy ra `filesDir`.
- APK sẽ nặng khoảng 80–150 MB. Không sao vì cài qua `adb install` hoặc copy file APK.

Riêng ML Kit thì không bundle được; model dịch do Google Play Services quản lý:
- Lần chạy đầu, màn **ModelSetup** gọi `RemoteModelManager.download()` cho `VIETNAMESE`, `ENGLISH`, `CHINESE` (cần Wi-Fi), rồi báo trạng thái.
- Sau đó app chạy **offline hoàn toàn**.
- Expose `Flow<ModelState>` (`NotDownloaded / Downloading / Ready / Error`) cho màn hình ModelSetup.
- Nếu sau này cần lên Google Play, chuyển model ASR sang tải lần đầu hoặc Play Asset Delivery (để phase sau).

---

## 6. Android specifics

### Permissions
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />            <!-- chỉ để tải model -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />  <!-- API 33+ -->
```

### Foreground Service
- `foregroundServiceType="microphone"`.
- Phải start service khi app đang ở foreground (giới hạn từ Android 14 với FGS microphone).
- Notification có nút **Dừng** và hiển thị câu dịch mới nhất.

### Audio
- `AudioRecord`, `MediaRecorder.AudioSource.VOICE_RECOGNITION`, 16 kHz, mono, PCM 16-bit.
- Đọc theo chunk khoảng 100 ms rồi chuyển sang float `[-1, 1]` cho sherpa-onnx.

### Lưu ý riêng cho Samsung (One UI)
One UI quản lý pin rất mạnh tay, có thể kill Foreground Service khi tắt màn hình lâu.
- Khi bắt đầu phiên dịch lần đầu, gọi `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (khai báo permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). Với app local, không lên Play, thì dùng được thoải mái.
- Hướng dẫn người dùng (trong màn Settings hoặc README) đặt app ở chế độ **Battery → Unrestricted** và **không** đưa vào danh sách "Sleeping apps / Deep sleeping apps".
- Test trên máy thật với màn hình tắt ≥ 30 phút (xem tiêu chí nghiệm thu).

### Build & cài đặt (local)
```bash
./gradlew assembleDebug                       # hoặc assembleRelease với keystore riêng
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
- Bật **Developer options → USB debugging** trên máy Samsung.
- Nếu cài bằng file APK copy vào máy: cho phép **Install unknown apps** với trình quản lý file đang dùng.
- Chỉ build `abiFilters += "arm64-v8a"` để APK nhẹ hơn (máy Samsung hiện đại đều là arm64).

### Hiệu năng
- ASR: `numThreads = 2` (thử 2–4 tuỳ máy), dùng model int8.
- Gom các câu final vào một hàng đợi dịch tuần tự (`Channel`) để không chạy nhiều lời dịch song song.
- Tạo engine **một lần** và tái sử dụng; giải phóng native resources trong `onDestroy`.

---

## 7. UI (MVP)

1. **ModelSetup**: trạng thái từng model, nút tải, thanh tiến độ, cảnh báo dung lượng.
2. **Live**:
   - Nút Bắt đầu / Dừng lớn.
   - Danh sách hội thoại: dòng gốc (kèm badge EN/ZH) và dòng tiếng Việt in đậm.
   - Câu đang nói hiển thị partial màu xám ở cuối danh sách.
   - Tự cuộn xuống câu mới nhất.
3. **Settings**: cỡ chữ phụ đề, chỉ tải model qua Wi-Fi, chọn engine dịch (ML Kit / ONNX khi đã có), bật/tắt TTS (phase 2).

---

## 8. Kế hoạch triển khai

### Phase 0 — Spike (xác nhận kỹ thuật)
- [ ] Chạy demo Android chính thức của sherpa-onnx với model zipformer zh-en trên máy thật; đo độ trễ và CPU.
- [ ] Viết app test ML Kit dịch en→vi, zh→vi offline (bật chế độ máy bay sau khi tải model).
- [ ] Chốt phiên bản dependency và URL model.

### Phase 1 — MVP
- [ ] `core/audio`: AudioRecorder → Flow.
- [ ] `core/model`: bundle model ASR/VAD trong assets + màn ModelSetup cho ML Kit.
- [ ] `feature/asr`: VAD + streaming ASR → `Flow<AsrEvent>`.
- [ ] `feature/translate`: LanguageDetector + MlKitTranslator.
- [ ] `feature/session`: TranslationSession + ForegroundService.
- [ ] `feature/ui`: màn Live + Settings.
- [ ] Unit test: LanguageDetector, ModelManager (checksum, state), hàng đợi dịch.
- [ ] Instrumented test: pipeline với file WAV mẫu EN/ZH (không dùng micro).

### Phase 2
- [ ] TTS tiếng Việt (Piper vi_VN qua sherpa-onnx), có tuỳ chọn tự đọc.
- [ ] `OnnxOpusTranslator` cho máy không có GMS (export Marian → ONNX int8, SentencePiece native, greedy decode).
- [ ] Lịch sử hội thoại (Room) + xuất text.

---

## 9. Tiêu chí nghiệm thu MVP

- [ ] Bật chế độ máy bay (sau khi đã tải model): nói một câu tiếng Anh thì bản dịch tiếng Việt hiện ra trong < 1 giây sau khi ngừng nói.
- [ ] Tương tự với một câu tiếng Trung.
- [ ] Badge ngôn ngữ đúng ≥ 95% với bộ câu test.
- [ ] Chạy nền liên tục 30 phút với màn hình tắt mà không crash, không bị hệ thống kill.
- [ ] Từ chối quyền micro thì có thông báo rõ ràng và nút mở Settings.
- [ ] Không có request mạng nào trong lúc dịch (kiểm tra bằng Network Profiler).

---

## 10. Rủi ro & lưu ý

| Rủi ro | Giảm thiểu |
|---|---|
| ML Kit chưa tải model khi đang offline | Màn ModelSetup chặn vào màn Live cho đến khi cả 3 language pack ở trạng thái Ready |
| One UI kill Foreground Service | Xin bỏ qua tối ưu pin, hướng dẫn đặt Unrestricted (xem mục 6) |
| Nhận dạng kém trong môi trường ồn | Dùng `VOICE_RECOGNITION` source; chỉnh ngưỡng VAD trong Settings (nâng cao) |
| Tốn pin khi chạy lâu | Chỉ dịch câu final; int8; giới hạn thread; VAD bỏ qua khoảng lặng |
| Câu dài bị VAD cắt giữa chừng | Cấu hình `max_speech_duration` và rule endpoint hợp lý; nối câu nếu khoảng nghỉ quá ngắn |
| Thay đổi tên/URL model upstream | Lưu URL + checksum trong một file config versioned, không hard-code rải rác |

---

## 11. Câu hỏi mở (cần chốt trước khi code)

~~1. Phát hành trên Google Play?~~ → **Đã chốt:** app local, cài qua APK.
~~2. Máy không có GMS?~~ → **Đã chốt:** Samsung, có GMS, dùng ML Kit.
3. Cần **TTS đọc bản dịch** ngay trong MVP không?
4. Có cần lưu **lịch sử** hội thoại không? Nếu có, giữ bao lâu?
5. Tên app, package name, và minSdk cuối cùng (model máy Samsung đang dùng để chọn minSdk/targetSdk).

---

## Tài liệu tham khảo
- sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx
- sherpa-onnx docs (Android, model list): https://k2-fsa.github.io/sherpa/onnx/
- ML Kit Translation (Android): https://developers.google.com/ml-kit/language/translation/android
- OPUS-MT: https://huggingface.co/Helsinki-NLP
- ONNX Runtime Mobile: https://onnxruntime.ai/docs/tutorials/mobile/
