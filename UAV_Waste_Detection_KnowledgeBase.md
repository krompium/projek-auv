# 🚁 UAV Waste Detection System — Project Knowledge Base & Developer Log

> **Dokumen Sintesis Berkelanjutan (Continuous Memory)**
> Digabungkan dari 5 file memori lintas-sesi AI (4 sesi Claude + 1 rangkuman Gemini).
> Rentang pengembangan terekam: **23 April 2026 → 7 Juli 2026**.
> Disusun sebagai basis pengetahuan tunggal agar konteks proyek dapat diwariskan penuh ke sesi AI berikutnya tanpa kehilangan detail teknis.

---

## 📑 Daftar Isi

1. [Konteks & Tema Proyek](#1-konteks--tema-proyek-project-overview--theme)
2. [Gaya Penulisan Kode & Konvensi](#2-gaya-penulisan-kode--konvensi-coding-style--conventions)
3. [Riwayat Pengembangan & Fitur](#3-riwayat-pengembangan--fitur-development-progress)
4. [Log Debugging & Pemecahan Masalah](#4-log-debugging--pemecahan-masalah-debugging--troubleshooting-log)
5. [Status Saat Ini & Langkah Selanjutnya](#5-status-saat-ini--langkah-selanjutnya-current-state--next-steps)
6. [Lampiran: Ringkasan Environment](#6-lampiran-ringkasan-environment)

---

## 1. Konteks & Tema Proyek (Project Overview & Theme)

### 1.1. Deskripsi & Tujuan Utama

**UAV Waste Detection System** adalah sistem deteksi sampah berbasis drone (UAV) dengan analitik geospasial. Tujuan utamanya adalah mendeteksi tumpukan/objek sampah (*rubbish*) secara *real-time* dari kamera drone saat terbang, mencatat lokasi geografisnya secara otomatis, lalu memvisualisasikan sebaran sampah tersebut sebagai peta panas (*heatmap*) untuk mendukung pengambilan keputusan operasional (evakuasi/pembersihan lahan sampah).

Proyek terdiri dari **dua komponen besar** yang saling terhubung melalui satu backend Firebase:

| Komponen | Peran | Stack Inti |
|---|---|---|
| **Aplikasi Mobile (Android)** | Penghimpun data lapangan (FPV + deteksi AI) di atas drone | DJI MSDK v4.18, DJI UX SDK 4.16.2, YOLOv8n TFLite INT8, Firebase Firestore |
| **Web Dashboard** | Pusat komando & *Geovisual Analytics* (heatmap, statistik) | HTML/CSS/JS, Google Maps Native API, Firebase Web SDK, Chart.js, KDE Heatmap |

Perangkat keras lapangan: **Drone DJI Mavic Air** dikendalikan lewat *Remote Controller* (RC), dengan smartphone terhubung ke RC via USB. Ponsel pengujian menggunakan SoC **MediaTek Helio G99** (GPU **Mali‑G57 MC2**, ~274 GFLOPS FP32, 2 *execution units* — kelas *budget*).

### 1.2. Development Journey (Ringkas)

Perjalanan pengembangan mengikuti alur dari "menempelkan AI ke sample resmi DJI" hingga "sistem geovisual analitik yang matang":

1. **Integrasi awal (April)** — Memodifikasi *Official DJI MSDK v4 Android Sample Code (v4.18)* untuk menyisipkan pipeline YOLO ke dalam feed video FPV. Puncaknya adalah menggambar *bounding box* di atas video (`OverlayView`).
2. **Perang melawan layar kosong & crash (April)** — Serangkaian debugging berat: *force close* (OutOfMemoryError), layar putih/hitam (blank), dan distorsi video. Keputusan arsitektural terbesar lahir di sini: **meninggalkan YUV callback** dan beralih ke `TextureView.getBitmap()`.
3. **Optimasi performa (April–Juli)** — Dari deteksi yang tersendat (*glitch/tearing*) menuju video mulus 30 FPS lewat *time-based throttling* dan *GPU delegate*.
4. **Fitur operasional (Mei)** — Firebase upload dengan deduplikasi, telemetri GPS, *preflight checks*, dan penataan ulang UI meniru **DJI Go 4** menggunakan UXSDK widgets.
5. **Web Dashboard (Mei–Juli)** — Membangun situs visualisasi heatmap dari data Firestore; migrasi dari Leaflet ke Google Maps Native API dengan analitik spasial-temporal tingkat lanjut.
6. **Penyempurnaan tracking (Juli)** — Fokus terakhir: mempercepat & memperhalus algoritma *object tracking* (trackId + IOU + SORT) di antara siklus deteksi.

### 1.3. Tema Visual, Pedoman UI/UX & Konsep Desain

**Tema global:** modern, profesional, dan meniru standar industri telemetri aviasi. Aplikasi mobile secara sadar meniru *layout* **DJI Go 4** agar terasa familiar bagi operator drone; dashboard web mengadopsi **Material Design** dengan kartu modern (*Modern Card UI*).

**Pedoman UI/UX Aplikasi Mobile (DJI Go 4-style):**

- **True Immersive / Full-Screen FPV** — menyembunyikan *action bar*, *status bar*, dan *Gesture Navigation Pill* Android demi pengalaman FPV penuh.
- **Top status bar** — meniru DJI Go 4: `PreFlightStatusWidget` di kiri; `FlightModeWidget`, `GPSSignalWidget`, `VisionWidget`, `RemoteControlSignalWidget`, `VideoSignalWidget`, `WiFiSignalWidget`, `BatteryWidget`, `ConnectionWidget` di kanan — ditambah TextView status Firebase.
- **Kontrol samping** — `TakeOffWidget` & `ReturnHomeWidget` (menggantikan tombol takeoff/land manual); `CameraControlsWidget` untuk foto/rekam.
- **Bottom** — `DashboardWidget` (kompas, altitude, speed, home distance) di `alignParentBottom`.
- **Minimap** — peta kecil di kiri-bawah (dinamis, *tap-to-expand*), diposisikan via *anchoring* murni (`layout_toLeftOf`, `layout_alignBottom`) agar tidak *overlap* dengan `CameraControlsWidget`.
- **Bounding box** — kotak deteksi merah (`#FF3B30`), label putih tebal di atas latar semi-transparan gelap (`#CC000000`); warna dikunci per `Track ID` agar identitas objek konsisten.
- **Pembersihan Ghost UI** — menghapus widget tak terpakai (`FocusModeWidget`/AF-MF, bayangan biru `RadarWidget`) yang mengganggu.

**Pedoman UI/UX Web Dashboard:**

- **Peta interaktif** dengan kontrol native Google (Satellite/Terrain/Roadmap, Show/Hide Labels, lokasi saat ini, 3D, Street View).
- **Heatmap kontinu** berbasis KDE (bukan kelas warna diskrit).
- **InfoWindow** kartu modern hasil *reverse geocoding* (alamat jalan dari lat/lng).
- **Responsif** (CSS media queries) — legenda, filter periode, dan grafik tidak *overlap* di layar ponsel.
- **Statistik** tren harian via Chart.js dengan sumbu X kronologis.

---

## 2. Gaya Penulisan Kode & Konvensi (Coding Style & Conventions)

### 2.1. Arsitektur & Design Patterns

- **Basis kode:** memperluas *Official DJI MSDK v4 Android Sample* — package root `com.dji.sdk.sample.*`. View kustom mengimplementasikan interface `PresentableView` DJI dan didaftarkan di `DemoListView` sebagai `GroupItem`.
- **Pattern View kustom Android** — `View`/`FrameLayout`/`RelativeLayout` yang meng-*inflate* layout XML dan mengelola *lifecycle* lewat `onAttachedToWindow()` / `onDetachedFromWindow()`.
- **Producer–Consumer / Async Frame Dropper** — frame video (producer) diproses inferensi di *background thread* (consumer); frame dibuang bila consumer sibuk (`isProcessingFrame`) alih-alih diantri, mencegah *memory buildup* & latensi.
- **Time-Based Throttling ("Metode Napas")** — gerbang berbasis waktu (`System.currentTimeMillis()`) mengontrol frekuensi inferensi, independen dari FPS kamera.
- **Callback bridge (Web)** — *bridge script* untuk menangani *race condition* antara modul ES6 Firebase dan Google Maps.
- **Event Bus** — `DJISampleApplication.getEventBus().post(...)` untuk event full-screen.

### 2.2. Konvensi Penamaan & Struktur

- **Bahasa:** Java (Android) & JavaScript ES6 (Web).
- **Konstanta:** `private static final` dengan `UPPER_SNAKE_CASE` — mis. `MODEL_FILE = "yolov8n.tflite"`, `LABELS_FILE = "labels.txt"`, `INPUT_SIZE = 640`, `MAX_DETECTIONS`, `MIN_CAPTURE_INTERVAL_MS`, `INFERENCE_INTERVAL_MS`.
- **Field:** `camelCase`; sebagian memakai prefix Hungarian ringan `m` untuk view (mis. `mVideoSurface`).
- **Flag lintas-thread:** `volatile` (mis. `volatile boolean isProcessingFrame`, `volatile YoloDetector yoloDetector`) — direkomendasikan naik ke `AtomicBoolean` + `compareAndSet` untuk anti *race condition*.
- **Warna:** hex ARGB via `Color.parseColor(...)` (mis. box `#FF3B30`, label bg `#CC000000`).
- **Koordinat model:** ternormalisasi `[0,1]` lalu diskalakan ke piksel view di `OverlayView.onDraw()`.
- **Web:** pemotongan presisi koordinat via `.toFixed(4)` (grid ~11×11 m), skala eksponensial `Math.pow()` untuk radius heatmap.

### 2.3. Best Practices yang Diterapkan

- **Threading disiplin:** load model & inferensi **wajib** di `HandlerThread`/`ExecutorService`/`new Thread`, **tidak pernah** di UI thread. Update UI dikembalikan via `new Handler(Looper.getMainLooper()).post(...)`.
- **Defensive guards:** null-check (`if (yoloDetector == null) return;`) dan NaN-check (`Double.isNaN(...)`) di setiap batas data sensor.
- **Manajemen memori eksplisit:** `bitmap.recycle()` tiap frame, `yoloDetector.close()` & `codecManager.cleanSurface()` di `onSurfaceTextureDestroyed`, `processingThread.quitSafely()`.
- **Catch `Throwable`, bukan hanya `Exception`** — agar `OutOfMemoryError`/`Error` ikut tertangkap.
- **On-device debugging** — karena USB ponsel dipakai koneksi RC (Logcat tak praktis), debugging memakai `Toast`, *global uncaught exception handler* penulis ke `/sdcard/crash_log.txt`, dan diagnostic overlay (dot merah, metrik Capture/Infer/Dropped).
- **Editing gaya "injection"** — perubahan dikomunikasikan sebagai *find this block → replace with* yang presisi, bukan menulis ulang file penuh.
- **Verifikasi berbasis metrik** — setiap optimasi diuji dengan angka nyata (Capture/Infer/Dropped) sebelum dianggap selesai.

### 2.4. Teknologi & Library Utama

| Domain | Teknologi |
|---|---|
| SDK Drone | DJI Mobile SDK (MSDK) **v4.18**, DJI **UX SDK 4.16.2** |
| Model AI | **YOLOv8n**, diekspor ke **TFLite Full Integer Quantization (INT8)** |
| Training | Ultralytics YOLO di **Google Colab** — `!yolo task=detect mode=export model=runs/detect/train/weights/best.pt format=tflite int8=True` |
| Inferensi | TensorFlow Lite Interpreter; **GPU Delegate** (`tensorflow-lite-gpu:2.14.0` + `tensorflow-lite-gpu-api:2.14.0`); TFLite Play Services (standalone) |
| Backend | **Firebase Firestore** — koleksi `waste_detections_v2` |
| Peta (Mobile) | OSMDroid (awal) → **Google Maps SDK Android** |
| Peta (Web) | Leaflet.js + Leaflet.heat (awal) → **Google Maps Native API** + HeatmapLayer |
| Grafik (Web) | **Chart.js** |
| Video | `DJICodecManager`, `VideoFeeder`, `TextureView` |

---

## 3. Riwayat Pengembangan & Fitur (Development Progress)

### 3.1. Integrasi Pipeline Deteksi ke Sample DJI (6 Langkah)

Fitur inti dibangun bertahap di atas sample DJI. Alur logikanya:

**`YoloDetector.java`** — kelas pemuat & pengeksekusi model. Memuat aset dari `app/src/main/assets/`:

```java
private static final String MODEL_FILE = "yolov8n.tflite";
private static final String LABELS_FILE = "labels.txt";
// Konstruktor:
loadLabels(context);                              // context.getAssets().open("labels.txt")
MappedByteBuffer modelBuffer = loadModelFile(context); // getAssets().openFd("yolov8n.tflite")
```

Prasyarat aset: kedua file berada di `assets/`, nama `.tflite` persis (lowercase, tanpa suffix `_float32`), `labels.txt` berisi tepat 80 baris tanpa baris kosong akhir.

**`OverlayView.java`** — `View` transparan di atas video, menggambar box dari list `Detection`. Koordinat ternormalisasi `[0,1]` diskalakan ke piksel; redraw dipicu lintas-thread via `postInvalidate()`:

```java
public void updateDetections(List<YoloDetector.Detection> newDetections) {
    this.detections = new ArrayList<>(newDetections);
    postInvalidate(); // aman dipanggil dari thread mana pun
}

@Override
protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float viewWidth = getWidth(), viewHeight = getHeight();
    for (YoloDetector.Detection det : detections) {
        RectF box = det.boundingBox;
        float left = box.left * viewWidth,  top = box.top * viewHeight;
        float right = box.right * viewWidth, bottom = box.bottom * viewHeight;
        canvas.drawRect(left, top, right, bottom, boxPaint);
        String labelText = det.label + " " + Math.round(det.confidence * 100) + "%";
        // ... gambar latar label + teks ...
    }
}
```

**`BaseFpvView.java`** — host `TextureView` + pipeline capture→infer→overlay (lihat §3.2). **`ObjectDetectionView.java`** — wrapper `PresentableView` yang menampung `BaseFpvView`, plus peta, telemetri, Firebase, preflight. Didaftarkan di **`DemoListView`** sebagai item menu *"Object Detection"* di grup Camera. Layout: `view_object_detection.xml` (host) + `view_fpv_and_camera_display.xml` (`<merge>` berisi `TextureView` + `OverlayView`).

### 3.2. Pipeline Capture → Inference → Overlay (Arsitektur Final)

Keputusan arsitektural utama: **tidak memakai YUV callback**, melainkan mengambil bitmap langsung dari `TextureView` di `onSurfaceTextureUpdated` (dipicu tiap frame), lalu memproses di background thread:

```java
@Override
public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    if (yoloDetector == null) return;
    if (mVideoSurface == null) return;

    // --- GERBANG WAKTU ("Metode Napas") ---
    long now = System.currentTimeMillis();
    if (now - lastCaptureStartTime < MIN_CAPTURE_INTERVAL_MS) return; // 400 ms
    lastCaptureStartTime = now;

    if (isProcessingFrame) { droppedFrameCount++; return; } // Async Frame Dropper
    isProcessingFrame = true;
    capturedFrameCount++;

    long captureStart = System.currentTimeMillis();
    final Bitmap frameBitmap = mVideoSurface.getBitmap(640, 640); // GPU→CPU copy di UI thread
    final long captureMs = System.currentTimeMillis() - captureStart;

    processingHandler.post(() -> {           // background thread
        try {
            long inferStart = System.currentTimeMillis();
            List<YoloDetector.Detection> detections = yoloDetector.detect(frameBitmap);
            final long inferMs = System.currentTimeMillis() - inferStart;
            frameBitmap.recycle();
            // diagnostic Toast tiap 30 frame: Capture/Infer/Dropped
            new Handler(Looper.getMainLooper()).post(() -> {
                if (overlayView != null) overlayView.updateDetections(detections);
                if (detectionCallback != null && !detections.isEmpty())
                    detectionCallback.onDetectionResult(detections);
            });
        } finally { isProcessingFrame = false; }
    });
}
```

Alur logika: video mentah drone → `VideoFeeder.getPrimaryVideoFeed()` → `codecManager.sendDataToDecoder(...)` merender ke `TextureView` (30 FPS mulus) → setiap ~400 ms satu frame di-*capture* jadi bitmap → inferensi YOLO di background (~5 FPS efektif) → hasil digambar `OverlayView` + dikirim ke callback Firebase.

### 3.3. Object Tracking (Spatio-Temporal)

Untuk mencegah pengiriman data berulang (*overcounting/spam*), model diperkuat memori pelacakan mandiri di dalam Java:

- **Centroid / SORT + IOU + Track ID** — pelacak spasial berbasis *Euclidean Distance*/IOU memberi `trackId` konsisten per objek; warna box dikunci ke `trackId`.
- **Deduplikasi upload** — `HashSet<Integer> uploadedTrackIds`; sebuah `trackId` hanya diunggah sekali.
- **Geo-Spatial Peak Aggregation** — hanya *peak count* per grid area (~11×11 m via `.toFixed(4)`) yang dicatat; data Firebase diperbarui hanya bila jumlah objek di titik bertambah — menekan latensi jaringan & duplikasi.

### 3.4. Firebase, Telemetri & Preflight (`ObjectDetectionView.java`)

**Upload dengan validasi & dedup:**

```java
private void tryUploadToFirebase(List<YoloDetector.Detection> detections) {
    if (Double.isNaN(currentLat) || Double.isNaN(currentLng)) return;
    if (currentLat == 0 && currentLng == 0) return;
    for (YoloDetector.Detection d : detections) {
        if (d.trackId <= 0) continue;
        if (d.confidence < 0.40f) continue;            // ambang confidence
        if (!uploadedTrackIds.contains(d.trackId)) {   // dedup
            uploadedTrackIds.add(d.trackId);
            uploadToFirebase(d, currentLat, currentLng, currentAlt);
        }
    }
}
```

Dokumen Firestore (`waste_detections_v2`) berisi: `label`, `confidence` (dibulatkan ×100), `trackId`, `jumlahDeteksi`, `latitude`, `longitude`, `altitude`, dan `timestamp` berformat `"MMMM dd, yyyy 'at' hh:mm:ss a z"`.

**Telemetri** — `FlightController.setStateCallback(...)` membaca lat/lng/alt/satelit; posisi drone digerakkan di `GoogleMap` (marker + `animateCamera`). **Preflight checks** — dijalankan otomatis (`postDelayed 2000ms`): cek izin lokasi, GPS ponsel, jumlah satelit (≥6), *flight mode*, dan baterai; menampilkan `AlertDialog` dengan tombol *Fix Now*.

**Fitur mobile lain yang selesai:** peta mini *tap-to-expand* (OSMDroid → Google Maps), tombol foto/rekam/gimbal, safety features (peringatan baterai lemah, RTH saat sinyal hilang), UI DJI Go 4 penuh via UXSDK widgets, `clearAllTracks()` untuk reset memori tracker.

### 3.5. Web Dashboard & Geovisual Analytics

- **Migrasi Leaflet → Google Maps Native API**, mengaktifkan widget native (Satellite/Terrain/Roadmap, labels, 3D, Street View) untuk meminimalkan *DOM deadlock*.
- **Hierarchical Spatial Clustering (Haversine)** — radius klaster adaptif per level zoom: Zoom 20+ *declustering* mutlak (radius 0 m, *ground truth*); Zoom 18–19 radius 5 m (*hotspot mikro*); Zoom <14 dikunci maks 100–200 m untuk menghindari **MAUP (Modifiable Areal Unit Problem)** sesuai standar akademik (UNEP).
- **Heatmap Kontinu (KDE)** — gradien interpolatif; ukuran titik nyala dihitung eksponensial (`Math.pow()`) agar menyusut proporsional saat *zoom out*, diselaraskan dengan *linear legend*.
- **Integritas Temporal** — transisi dari *sliding window* (rawan *temporal leakage*) ke *Calendar Boundary* murni; perbaikan RegEx parser untuk `Invalid Date` & urutan kronologis Chart.js.
- **Reverse Geocoding** — lat/lng → nama/alamat jalan via `Geocoder`, ditampilkan di *Modern Card InfoWindow*.
- **Responsivitas** — CSS media queries pada `statistics.html` & FPV dashboard.

### 3.6. Keputusan Teknis Penting (Technical Decisions)

1. **Buang YUV callback, pakai `TextureView.getBitmap()`** — karena `enabledYuvData(true)` mematikan rendering ke surface pada versi SDK ini, dan `enableHardwareDecode()` tidak ada. Ini menyederhanakan pipeline (tanpa konversi NV12→NV21) dan mengembalikan tampilan video.
2. **Gunakan `getPrimaryVideoFeed()`** (bukan `getSecondaryVideoFeed()`) untuk Mavic Air.
3. **Muat `YoloDetector` hanya sekali, di background thread** — bukan di dua instance `VideoFeedView` (mencegah OOM).
4. **Time-based throttling (400 ms) > frame-counting modulo** — adaptif terhadap FPS tak diketahui & *thermal drift*.
5. **GPU Delegate** untuk akselerasi inferensi; wajib menambah artifact `tensorflow-lite-gpu-api`.
6. **`getState()` one-time read** untuk preflight — bukan `setStateCallback()` yang menimpa callback telemetri.
7. **Migrasi peta web ke Google Maps Native** untuk stabilitas & fitur.
8. **Calendar Boundary** untuk statistik temporal, bukan aritmetika durasi.

---

## 4. Log Debugging & Pemecahan Masalah (Debugging & Troubleshooting Log)

> Bagian ini mendokumentasikan setiap bug signifikan: **gejala → investigasi → akar masalah → solusi final**.

### BUG-01 — Bounding box tidak muncul (verifikasi aset dulu)

- **Gejala:** deteksi berjalan tapi tak ada box.
- **Investigasi:** verifikasi penempatan `yolov8n.tflite` & `labels.txt` di `assets/` (nama persis, 80 label). Aset OK → bukan *human error*, ini isu kode/tampilan.
- **Akar masalah (awal):** `setYuvDataCallback` didaftarkan tapi `enabledYuvData(true)` tak dipanggil → callback tak pernah *fire*.

### BUG-02 — YUV mematikan tampilan video (KEPUTUSAN ARSITEKTURAL)

- **Gejala:** setelah `enabledYuvData(true)` ditambahkan, deteksi jalan ("person 80%") **tetapi video blank putih**. `enableHardwareDecode(true)` yang disarankan **tidak ada** di SDK ini.
- **Akar masalah:** pada MSDK versi ini, `enabledYuvData(true)` menghentikan render ke surface `TextureView` dan hanya mengirim data ke callback.
- **Solusi final:** **hapus total jalur YUV.** Ambil frame langsung dari `TextureView.getBitmap(640,640)` di `onSurfaceTextureUpdated`. Juga ganti `getSecondaryVideoFeed()` → `getPrimaryVideoFeed()`. Ini menghilangkan kebutuhan konversi NV12→NV21 dan mengembalikan video.

### BUG-03 — Force Close saat buka menu Video Feeder (OutOfMemoryError)

- **Gejala:** app *force close* tanpa Toast, meski sudah dibungkus `try-catch (Exception e)`.
- **Investigasi:** Logcat tak praktis (USB dipakai RC). Dicoba: Toast crash-catcher, lalu *global uncaught exception handler* → `/sdcard/crash_log.txt`.
- **Akar masalah:** (1) `OutOfMemoryError` adalah `Throwable`, **bukan** `Exception`, sehingga lolos dari catch; (2) ada **dua instance** `VideoFeedView` (`primary_video_feed` + `fpv_video_feed`) yang masing-masing memuat `YoloDetector` → dua model penuh di memori.
- **Solusi final:**
  ```java
  } catch (Throwable e) { /* tangkap OOM juga */ }
  ```
  dan muat `YoloDetector` **hanya** pada view yang menerima overlay:
  ```java
  public void setOverlayView(OverlayView overlay) {
      this.overlayView = overlay;
      if (overlay != null && yoloDetector == null) {
          yoloDetector = new YoloDetector(getContext().getApplicationContext());
      }
  }
  ```
- **Follow-up:** karena force close berlanjut, arsitektur di-*pivot* ke `ObjectDetectionView` + `BaseFpvView` (memakai `view_fpv_and_camera_display.xml`) yang lebih bersih & dapat dikustomisasi.

### BUG-04 — `YoloDetector` dibuat dua kali di `BaseFpvView`

- **Akar masalah:** detector diinisialisasi di main thread **dan** di background thread; versi main-thread menyebabkan crash.
- **Solusi:** hanya buat sekali di background thread; field dijadikan `volatile YoloDetector yoloDetector`.

### BUG-05 — Box tidak tergambar walau deteksi ada (koordinat)

- **Investigasi:** ditambah debug — menggambar titik merah di pojok + log `getWidth()/getHeight()/detections.size()`. Titik merah muncul → `onDraw` *fire*.
- **Akar masalah:** koordinat *bounding box* berada di luar area tampak / pemetaan `[0,1]`→piksel; sempat diperparah oleh `getBitmap()` yang menangkap `TextureView` yang sudah diubah `updateTextureViewTransform()` sehingga berisi *black border*.
- **Solusi:** tangkap pada ukuran render aktual `getBitmap(mVideoSurface.getWidth(), mVideoSurface.getHeight())` dan pastikan `OverlayView` transparan.

### BUG-06 — Distorsi video, white strip & black strip

- **Gejala:** video ada garis putih di pinggir, lalu garis hitam di dalam kotak video; sempat tampak *portrait/distorted*.
- **Akar masalah:** (1) `updateTextureViewTransform()` (matrix transform manual) **berkelahi** dengan codec DJI; (2) codec merender pada **resolusi native** (1280×720 / 1920×1080) sesuai dimensi saat `DJICodecManager` diinisialisasi, tidak *scale-up* ke `TextureView` yang lebih besar → sisa area hitam; (3) *white border* = view belum benar-benar full-screen.
- **Solusi final:** **hentikan melawan codec** — hapus `updateTextureViewTransform()` beserta semua pemanggilnya dan field `videoWidth/videoHeight`; inisialisasi codec dengan dimensi tetap (1920×1080); tambahkan `FLAG_FULLSCREEN` di `onAttachedToWindow` dan `clearFlags` di `onDetachedFromWindow`.

### BUG-07 — Lag & glitch performa (optimasi)

- **Akar masalah:** `getBitmap()` menyalin tekstur penuh 1920×1080 GPU→CPU di UI thread; `createScaledBitmap` alokasi tiap frame; Toast spam tiap deteksi; NMS memindai `MAX_DETECTIONS = 8400` kandidat.
- **Solusi (paket optimasi):** capture lebih kecil (`getBitmap(640,360)`), naikkan *frame skip* (`% 3` → `% 5`), buang Toast spam, turunkan `MAX_DETECTIONS` 8400 → 300 (YOLOv8n jarang >50 deteksi nyata). Estimasi: getBitmap ~70% lebih cepat, NMS ~90% lebih cepat.

### BUG-08 — Overlay update ter-*comment* (deteksi berhenti tampil)

- **Akar masalah:** blok `overlayView.updateDetections(...)` di-*comment out* → deteksi dihitung tapi tak pernah dikirim ke overlay.
- **Solusi:** *uncomment* & bersihkan (hapus Toast + `reusableBitmap` yang tak berguna karena `getBitmap()` selalu mengalokasi baru).

### BUG-09 — 🔴 Frame Drop / Screen Tearing (SOLUSI DEFINITIF)

- **Gejala:** layar FPV patah-patah (*tearing* kanan→kiri), *tracker drop*, box tertinggal di belakang objek. Counter **Dropped** naik terus (66→95).
- **Akar masalah:** **Main UI Thread Starvation** — `getBitmap()` + inferensi membekukan rendering; capture tiap ~167 ms (setiap frame ke-5 @30fps) sementara inferensi 250–573 ms, jadi pekerjaan baru datang sebelum yang lama selesai → *backlog*.
- **Solusi definitif — Time-Based Throttling ("Metode Napas"):**
  ```java
  private long lastCaptureStartTime = 0;
  private static final long MIN_CAPTURE_INTERVAL_MS = 400; // > peak infer, dgn margin

  long now = System.currentTimeMillis();
  if (now - lastCaptureStartTime < MIN_CAPTURE_INTERVAL_MS) return;
  lastCaptureStartTime = now; // di-update SEBELUM cek isProcessingFrame (1 attempt/window)
  ```
  Detail penting: `lastCaptureStartTime` diperbarui **sebelum** cek `isProcessingFrame`, agar tidak *re-check* tiap ~33 ms saat sibuk. Video decoder DJI kini bebas hambatan → **FPV mulus 30 FPS**, inferensi ~5–7 FPS.
- **Hasil terukur:** `Dropped: 0` konsisten (dari yang tadinya naik 3–9 tiap pembacaan). Bonus: Infer turun 248–338 ms → **202–232 ms**.

### BUG-10 — Kenapa Infer justru lebih cepat? (thermal & warmup)

- **Analisis:** dengan jeda ~400 ms, GPU Mali‑G57 mendapat ~178 ms *idle* per siklus → berjalan lebih dingin, menahan *clock* lebih tinggi (mengurangi *thermal throttling*). "Beginning frame drop lalu mulus" = **shader compilation** GPU delegate (beberapa ms s/d ~0.5 s) + **DVFS ramp-up** governor. Opsional: *warm-up inference* dengan bitmap dummy saat init untuk membayar biaya itu di muka.

### BUG-11 — GPS mengunggah `NaN`

- **Akar masalah:** cek lama `if (currentLat == 0 && currentLng == 0)` tidak menangkap `NaN` (karena `NaN != 0`). SDK mengembalikan `NaN` sebelum GPS *lock*.
- **Solusi:** tambah `Double.isNaN(...)` guard di `tryUploadToFirebase` **dan** hanya simpan koordinat bila valid di `startTelemetry`.

### BUG-12 — 🔴 Callback telemetri tertimpa (Firebase berhenti upload)

- **Akar masalah:** `runPreflightChecks()` (dan `setupSafetyFeatures()`) memanggil `fc.setStateCallback(...)` yang **menimpa** callback dari `startTelemetry()`. Setelah preflight jalan sekali (`checked = true`), `currentLat/currentLng` **berhenti diperbarui** → Firebase tak pernah dapat koordinat valid.
- **Solusi:** ganti preflight ke pembacaan **satu kali** yang tidak menimpa callback:
  ```java
  fc.getState(new CommonCallbacks.CompletionCallbackWith<FlightControllerState>() {
      @Override public void onSuccess(FlightControllerState state) { /* cek sats/mode/battery */ }
      @Override public void onFailure(DJIError error) { /* tampilkan apa adanya */ }
  });
  ```
- **Catatan lapangan:** 6+ satelit hanya tercapai **di luar ruangan** dengan pandangan langit terbuka (indoor = 0). Tunggu 30–60 dtk setelah power-on.

### BUG-13 — API MSDK v4 tidak ada / berbeda nama

- `isIMUPreheated()` & `doesNeedCompassCalib()` → tidak tersedia/berbeda nama di `FlightControllerState` MSDK v4.18 (dihapus/diganti). Beberapa method battery juga tidak ada → dipakai pola **caching** nilai dari callback state alih-alih memanggil API yang tak ada.

### BUG-14 — Tujuh isu layout UI (DJI Go 4)

Diperbaiki sekaligus memakai `activity_default_widgets.xml` resmi sebagai basis: (1) Map *overlap* `CameraControlsWidget` → pindah ke kiri-bawah; (2) `PreFlightCheckListPanel` default `visibility="gone"`, toggle via `PreFlightStatusWidget`; (3) `DashboardWidget` `alignParentBottom`; (4) reposisi status bar (PreFlightStatus kiri; sisanya kanan + status Firebase); (5) klarifikasi menu tiga-titik; (6) hapus `FocusModeWidget` (AF/MF); (7) hilangkan *ghost* bentuk biru di bar bawah.

### BUG-15 — 🔴 GPU Delegate: `getBestOptionsForThisDevice` "error"

- **Investigasi:** dikira `getBestOptionsForThisDevice()` tak ada di paket lama; dikonfirmasi lewat docs **ada** & valid (pola `new CompatibilityList()` → `isDelegateSupportedOnThisDevice()` → `getBestOptionsForThisDevice()`). Ditemukan bug dokumentasi Google: contoh menulis `CompatibilityList compatList = CompatibilityList();` **tanpa `new`** (Java tak valid).
- **Log sebenarnya:** `class file for org.tensorflow.lite.gpu.GpuDelegateFactory not found`.
- **Akar masalah:** `GpuDelegate.Options` adalah **subclass** dari `GpuDelegateFactory.Options`; konstruktor `GpuDelegate` menerima `GpuDelegateFactory.Options`. Kelas `GpuDelegateFactory` **tidak** ada di `tensorflow-lite-gpu`, melainkan di artifact terpisah `tensorflow-lite-gpu-api`.
- **Solusi final:**
  ```groovy
  implementation 'org.tensorflow:tensorflow-lite-gpu:2.14.0'
  implementation 'org.tensorflow:tensorflow-lite-gpu-api:2.14.0' // ← wajib ditambah
  ```
  Proguard `-keep class org.tensorflow.lite.gpu.** { *; }` sudah mencakup `GpuDelegateFactory` (package sama). Hasil: **"GPU Delegate: Active"**.
- **Catatan minor (technical debt):** `CompatibilityList` implements `AutoCloseable` & memegang resource native — idealnya dibungkus *try-with-resources*; kode saat ini belum menutupnya (kebocoran kecil).

### BUG-16 — GPU Active tapi masih drop (kalibrasi throttle)

- **Data (setelah GPU):** Infer 248–338 ms (turun ~45% dari 573 ms CPU) — tetapi masih 1.5–2× di atas ambang ~167 ms → Dropped tetap naik.
- **Analisis hardware:** Mali‑G57 MC2 Helio G99 (~274 GFLOPS, 250 MHz–~1.07 GHz) memang *budget-tier*; ~50% adalah *ceiling* realistis. NNAPI/NeuroPilot bisa diuji tapi kemungkinan hanya kembali ke GPU/CPU (ekspektasi rendah).
- **Solusi:** bukan mempercepat inferensi, tapi **menyamakan laju capture** → gerbang 400 ms (BUG-09) → Dropped=0. Divisor N=10–12 dipertimbangkan, tetapi gerbang **berbasis waktu** dipilih karena adaptif.

### BUG-17 — Keandalan "Asynchronous Frame Dropper" & perbaikan

- **Temuan:** "Async Frame Dropper" bukan algoritma akademik baku, melainkan *design pattern* (mirip `STRATEGY_KEEP_ONLY_LATEST` CameraX / ML Kit "drop the frame"). Kode saat ini **sudah** mengimplementasikannya via `isProcessingFrame`.
- **Kelemahan & perbaikan yang direkomendasikan (belum tentu semua diterapkan):**
  1. **Race condition** → ganti `volatile boolean` dengan `AtomicBoolean` + `compareAndSet(false, true)`.
  2. **Bounding box lag** → tambah *frame interpolation*.
  3. **Skip tidak adaptif** → *adaptive frame skip* (sesuaikan `frameSkipRate` dari waktu inferensi; riset "YOLO with Adaptive Frame Control").
  4. **Alternatif** → `PixelCopy` API (Android 8+) untuk GPU copy async tanpa memblok UI thread.

### BUG-18 — Race condition pada Web (Blank Page)

- **Akar masalah:** asinkronitas antara modul ES6 Firebase & skrip Google Maps → inisialisasi peta jalan sebelum UI siap → "Layar Hitam".
- **Solusi:** *Bridge Script* (callback logic) sebagai jembatan pengaman agar init peta hanya setelah seluruh antarmuka dimuat browser.

---

## 5. Status Saat Ini & Langkah Selanjutnya (Current State & Next Steps)

### 5.1. Posisi Proyek (per 7 Juli 2026)

**Aplikasi Mobile — pipeline inti solid & stabil:**

> **DJI feed → GPU-accelerated YOLOv8n → Firestore logging**, berjalan bersih dengan **Dropped: 0** dan **video FPV mulus 30 FPS** (inferensi ~5 FPS efektif, ~202–232 ms/frame).

Yang sudah rampung: integrasi YOLO + overlay, GPU Delegate aktif, time-based throttling, Firebase upload + deduplikasi + validasi NaN, telemetri GPS, preflight checks (via `getState`), UI DJI Go 4 (UXSDK widgets), full-screen immersive, safety features.

**Web Dashboard — fungsional & matang:** Google Maps Native API, heatmap KDE kontinu, hierarchical spatial clustering (Haversine, anti-MAUP), reverse geocoding InfoWindow, integritas temporal Calendar Boundary, statistik Chart.js, responsif.

### 5.2. Fokus Terakhir yang Sedang Dikerjakan (Open Thread)

**Menghaluskan Object Tracking.** Konsekuensi dari gerbang 400 ms: `OverlayView` hanya menerima posisi box baru tiap ~400 ms, sehingga di antara update box "diam" sementara objek/drone bergerak → **box glitch/lag, tidak mulus mengikuti objek**. Kode tracking (`trackId` + IOU + SORT) **sudah ada** di dalam file terkait (`ObjectDetectionView.java` / `YoloDetector.java`), tetapi butuh dibuat **lebih cepat & halus**. Percakapan terakhir (7 Juli) berhenti tepat saat menganalisis file untuk menemukan *bottleneck* tracking — belum ada solusi final yang diterapkan.

### 5.3. Fitur Belum Selesai & Technical Debt

**Prioritas (menyambung fokus terakhir):**

- **Interpolasi/prediksi antar-frame** — perhalus box di antara siklus deteksi 400 ms (mis. *linear interpolation* atau *Kalman filter* untuk memprediksi posisi).
- **`AtomicBoolean` + `compareAndSet`** — perbaiki potensi *race condition* pada `isProcessingFrame`.
- **Adaptive frame skip** — sesuaikan laju berdasar waktu inferensi aktual.
- **PixelCopy API** — alternatif async pengganti `getBitmap()` untuk mengurangi beban UI thread (opsional).

**Roadmap riset & sistem (dari rangkuman Gemini):**

1. **Firebase Offline Persistence** — nyalakan persistensi SDK Android (Local SQLite) agar drone di area minim sinyal (pantai/rawa) tetap menyimpan data *offline* lalu *auto-sync* ke pusat komando saat koneksi pulih.
2. **Field Stress Test** — uji batas kecepatan rotasi/terbang vs *tracker drop* akibat **oklusi**; tentukan `CONFIDENCE_THRESHOLD` ideal dengan *False Positive* terkecil.
3. **Frame Skipping Aktif** — hentikan inferensi beban tinggi saat drone statis (tanpa *motion*) untuk mencegah **thermal throttling**.

**Debt kecil / kebersihan kode:**

- `CompatibilityList` tidak ditutup (kebocoran resource native kecil) → bungkus *try-with-resources*.
- Field `frameCount` kini tak terpakai di `onSurfaceTextureUpdated` setelah beralih ke gerbang waktu — aman ditinggal, cukup dibersihkan bila mau.
- **Warm-up inference** opsional (bitmap dummy saat init) untuk menghilangkan *jank* beberapa detik awal (shader compile + DVFS ramp).
- Eksperimen **NNAPI/NeuroPilot** (ekspektasi rendah pada Helio G99).

---

## 6. Lampiran: Ringkasan Environment

| Aspek | Nilai |
|---|---|
| Drone | DJI Mavic Air (via Remote Controller, ponsel ⇄ RC via USB) |
| Ponsel uji (SoC) | MediaTek Helio G99 — GPU Mali‑G57 MC2 (~274 GFLOPS FP32, 2 EU, 250 MHz–~1.07 GHz) |
| Baseline performa | CPU infer ~573 ms → GPU ~250–340 ms → +throttle ~202–232 ms |
| Ambang backlog | capture ~167 ms (frame ke-5 @30fps) → dinaikkan ke **400 ms** |
| Firestore collection | `waste_detections_v2` |
| Ambang deteksi upload | `confidence >= 0.40` |
| Input model | 640×640, YOLOv8n INT8 |
| Lokasi pengembang | Bandung, Jawa Barat, Indonesia (mahasiswa Teknik Komputer) |

---

*Dokumen ini adalah hasil sintesis dari: `Claude-Continuing a shared conversation.md` (riwayat penuh Apr–Jul 2026), `Claude-Continuing a previous conversation.md` (saga GPU Delegate), `UAV_Waste_Detection_Summary.md` (rangkuman Gemini), beserta duplikat berstempel-waktu masing-masing. Disusun sebagai Project Knowledge Base tunggal — 7 Juli 2026.*

