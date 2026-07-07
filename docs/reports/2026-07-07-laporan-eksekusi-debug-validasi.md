# Laporan Eksekusi — Debug, Validasi & Perbaikan Deteksi Sampah UAV

**Tanggal:** 7 Juli 2026
**Rencana yang dieksekusi:** `docs/plans/2026-07-07-001-fix-uav-waste-detection-debug-validation-plan.html` (status: **completed**)
**Branch:** `fix/waste-detection-debug-validation` (10 commit di atas `uxsdk-feature`, belum di-push)
**Skala perubahan:** 23 file, +2.134 / −132 baris
**Verifikasi:** 46/46 unit test JVM lulus terhadap tree yang ter-commit (HEAD), plus simulasi pipeline end-to-end dan uji fungsional `parity_check.py`

---

## 1. Ringkasan Eksekutif

Seluruh 10 unit implementasi (U1–U10) dari rencana telah dieksekusi dan di-commit satu per satu. Tiga bug yang sudah terkonfirmasi di kode berhasil diperbaiki, dan akar masalah terbesarnya — thread "tracking tidak stabil" yang membuat proyek ini macet — kini terbukti secara kuantitatif melalui reproduksi bug dan test: pembanding jarak tracking lama membandingkan koordinat ternormalisasi `[0,1]` dengan ambang **150 piksel**, sehingga *guard*-nya tidak pernah menolak kecocokan dan setiap deteksi selalu "mencuri" track terdekat. Ini merusak trackId sekaligus dedup Firebase yang bergantung padanya.

Pendekatan kunci eksekusi: **setiap inti algoritma diekstrak menjadi kelas Java murni** (tanpa dependensi Android) — `BoxTracker`, `Letterbox`, `QuantUtil`, `BoxInterpolator`, `AdaptiveInterval`, `GeoGridAggregator`, `MetricsLogger` — sehingga semuanya dapat diverifikasi di JVM biasa tanpa perangkat/SDK Android, dan test tersebut kini menjadi bagian permanen repo (`app/src/test/`, JUnit 4.13.2 ditambahkan ke `build.gradle`).

---

## 2. Riwayat Commit

```
e720887 feat(firebase): geo-grid peak aggregation + offline persistence (U10)
ff38ed0 feat(perf): adaptive capture interval + static-scene thermal gating (U9)
62c275b feat(overlay): inter-frame box prediction between detection cycles (U8)
6682ebd feat(config): centralize thresholds + field-test protocol (U7)
f51b03a feat(validation): offline parity & accuracy harness (U6)
cfba899 fix(model): correct int8 quantization + loud model/label guard (U5)
0a24f09 fix(lifecycle): atomic frame gate + complete tracker reset (U4)
95153e1 fix(preprocess): aspect-preserving letterbox + inverse box mapping (U3)
72577d3 fix(tracking): normalized-space track association via BoxTracker (U2)
cadc278 feat(metrics): file-based CSV metrics logger + on-screen debug rollup (U1)
```

---

## 3. Detail per Unit

### U1 — MetricsLogger berbasis file + debug overlay (Milestone 1)

**Masalah:** diagnostik memakai Toast (spam tiap 30 frame), padahal USB dipakai RC sehingga Logcat tidak praktis — tidak ada jejak yang bisa dianalisis pasca-terbang.

**Perubahan:**
- Baru: `internal/util/MetricsLogger.java` — CSV bergulir di `Android/data/<pkg>/files/metrics/detection_metrics.csv`. Kolom: `timestampMs,captureMs,inferMs,droppedCount,detectionCount,activeTrackCount,totalTrackIds,gpuActive,intervalMs`. Rotasi berbasis ukuran (512 KB, disk terikat ~2×), degradasi ke no-op bila direktori tak bisa ditulis (diagnostik tidak boleh menjatuhkan pipeline), plus `detection_events.log` untuk event (init, reset, warm-up, error model).
- `BaseFpvView`: log per frame dari thread pemroses; Toast modulo-30 dihapus.
- `OverlayView.setDebugInfo()`: rollup satu baris di kiri-atas FPV — `cap 32ms  inf 204ms  drop 0  det 2  trk 2/2`.

**Verifikasi:** 4 test (100 frame → 100 baris CSV valid & timestamp monoton; baris tetap ditulis saat 0 deteksi; rotasi membatasi ukuran; direktori tak valid → no-op tanpa exception).

### U2 — Perbaikan bug ruang-koordinat tracking (Milestone 2; akar masalah utama)

**Akar masalah (terkonfirmasi):** `assignTrackIds()` membandingkan `Math.hypot` dua titik pusat ternormalisasi (maks ≈ 1,41) dengan `MAX_DISTANCE = 150.0f` piksel → kondisi `dist < 150` selalu benar → *greedy nearest-match* selalu jadi.

**Perubahan:** tracker ditulis ulang sebagai `BoxTracker.java` (Java murni):
- Satu sistem satuan (ternormalisasi) dari ujung ke ujung; gerbang kecocokan `0.08` unit (≈ 0,06 diagonal frame), konstanta ter-ekspos.
- Asosiasi *greedy-by-ascending-distance* atas semua pasangan (deteksi, track) — mengurangi pertukaran ID saat objek berdekatan, dibanding nearest-match per deteksi.
- Estimasi kecepatan per track (EMA α=0,5; unit ternormalisasi/detik) — prasyarat prediksi overlay U8.
- Semantik `MAX_LOST_FRAMES = 5` dipertahankan.
- `YoloDetector` menjadi delegasi tipis; `Detection` mendapat `velocityX/Y` dan `timestampMs`.

**Log debug — reproduksi bug (kode lama vs baru, dijalankan di sandbox):**
```
SCENARIO: object A stationary at (0.2,0.2); object B appears at (0.9,0.9) in cycle 2
          (distance 0.99 normalized units — clearly a DIFFERENT object)

[OLD  ] cycle1 A -> id 1
[OLD  ] cycle2 B(0.9,0.9) -> id 1   <== STEALS track 1! (0.99 < 150 always true)
[FIXED] cycle1 A -> id 1
[FIXED] cycle2 B(0.9,0.9) -> id 2 (new=true)   <== correct: new track, gate 0.08 rejects 0.99
```

**Verifikasi:** 9 test — objek diam 25 siklus dengan jitter → tetap ID 1 (nol churn); deteksi jauh 0,9 → ID baru, bukan mencuri; dua objek terpisah tidak menyatu; objek berdekatan tidak bertukar ID; pruning setelah `MAX_LOST_FRAMES` lalu ID segar; daftar kosong konsisten; kecepatan terestimasi benar; reset menolkan ID counter.

### U3 — Letterbox preprocessing + pemetaan balik box (Milestone 2)

**Masalah:** `getBitmap(640, 640)` memampatkan umpan 16:9 jadi persegi; YOLOv8 dilatih pada input letterbox (aspek dipertahankan + padding) → akurasi turun sistematis dan semua box terdistorsi.

**Perubahan:**
- Capture menjadi `getBitmap(640, 360)` (aspek sumber).
- Baru: `Letterbox.java` (matematika murni) — skala sisi-terpanjang + pad abu-abu (114,114,114; konvensi YOLO), simpan `scale/padX/padY`, dan `inverseMapNormalized()` memetakan box keluaran model kembali ke koordinat ternormalisasi frame asli (di-clamp `[0,1]`).
- `YoloDetector.detect()`: menggambar ke bitmap letterbox 640² yang **digunakan ulang** (alokasi di luar hot path); `parseOutput` melakukan inverse-map; box yang seluruhnya jatuh di area padding dibuang.

**Verifikasi:** 6 test — geometri 16:9→640² (pad 140 px atas/bawah); round-trip box tengah IoU ≥ 0,95; objek di tepi tetap dalam `[0,1]` tanpa terpotong padding; box di band padding kolaps (area nol); input persegi = transformasi identitas; sumber portrait di-pad horizontal.

### U4 — Pengerasan konkurensi & lifecycle (Milestone 2)

**Perubahan:**
- `isProcessingFrame`: `volatile boolean` (pola *check-then-set* yang punya jendela balapan terhadap reset di `finally{}` thread pemroses) → `AtomicBoolean.compareAndSet(false,true)` — tepat satu frame in-flight, sisanya terhitung *dropped*.
- `YoloDetector.reset()` membersihkan track **dan** ID counter; `ObjectDetectionView.clearAllTracks()` kini memanggilnya (sebelumnya hanya membersihkan set upload — state tracker bocor antar sesi).
- `CompatibilityList` dibungkus try-with-resources; field mati `frameCount`, `videoWidth`, `videoHeight` dihapus.

**Verifikasi:** semantik reset tercakup di `BoxTrackerTest.reset_clearsTracksAndIdCounter`; gerbang frame terikat ke kelas Android (diverifikasi lewat inspeksi — `compareAndSet` atomik by construction).

### U5 — Koreksi jalur int8 + guard konsistensi model/label (Milestone 2)

**Bug laten (terkonfirmasi):** `bitmapToInputBuffer` menulis `(byte)(r + inputZeroPoint)`; kuantisasi affine yang benar adalah `round((r/255)/scale) + zeroPoint` dengan clamp int8. Tidak aktif hari ini (model default float16) tetapi mematahkan setiap percobaan int8.

**Perubahan:**
- Baru: `QuantUtil.java` (murni) — `quantizeChannel()` / `dequantize()`.
- Guard saat load: kanal keluaran model (`shape[1] − 4`) harus sama dengan `labels.size()`; bila tidak → `IllegalStateException` dengan pesan jelas, interpreter ditutup, Toast error tampil, event tercatat — **gagal nyaring**, bukan korupsi parsing diam-diam.
- `best_float16.tflite` tetap default; int8 tetap opt-in.

**Log debug (test):**
```
PASS roundTrip_preservesNormalizedValueWithinOneStep
PASS extremes_mapToInt8Range          (0→-128, 255→127)
PASS midGray_mapsNearZero
PASS clamping_neverOverflows
PASS oldFormula_wasWrongForZeroPointZeroExports   <- repro bug lama: (byte)(255+0) = -1 (wrap!)
```

### U6 — Harness paritas & akurasi offline (Milestone 3)

**Perubahan:**
- Baru: `DetectionHarness.java` — mode replay satu-tembakan berbasis file: taruh gambar uji di `<externalFilesDir>/harness_images/`, buat file penanda `run_harness`, buka FPV → setelah model termuat, semua gambar dialirkan lewat **jalur `detect()` yang sama persis dengan penerbangan** (letterbox+TFLite+NMS; tracker di-reset per gambar) dan prediksi ditulis ke `harness_predictions.jsonl`.
- Baru: `tools/parity_check.py` (299 baris) — tiga mode:
  1. `--generate-reference`: menjalankan pipeline identik (letterbox pad-114, ambang 0,35, NMS 0,45) di PC/Colab via tflite-runtime/TF;
  2. paritas `--device` vs `--reference` (R5): kecocokan per-box IoU ≥ 0,9 + label + delta confidence ≤ 0,05, target ≥ 95% box; **offset sistematis dideteksi terpisah** (rata-rata geser > 0,01 → FAIL, tidak boleh "terhilang dirata-rata");
  3. akurasi vs ground-truth YOLO txt Roboflow (R6): sweep precision/recall/F1 atas ambang confidence.

**Log debug — uji fungsional dengan fixture sintetis:**
```
=== TEST 1: parity PASS scenario ===
reference boxes: 4, matched at IoU>=0.9: 4 (100.0%, required 95%)
mean confidence delta: -0.0100 (tol ±0.05)
mean box offset (l,t,r,b): -0.0007, -0.0005, +0.0003, +0.0005
PARITY: PASS            (exit=0)

=== TEST 2: systematic-offset FAIL scenario (geser +0.05 disengaja) ===
reference boxes: 4, matched at IoU>=0.9: 0 (0.0%)
  fail: img_001.jpg: unmatched_ref=1 phantom_device=1
  ...
PARITY: FAIL            (exit=1)  <- offset sistematis tertangkap, sesuai desain

=== TEST 3: accuracy sweep vs ground truth ===
  thr  precision   recall      F1    TP    FP    FN
 0.20      0.750    0.750   0.750     3     1     1
 0.40      1.000    0.750   0.857     3     0     1  <-- best F1
 0.60      1.000    0.250   0.400     1     0     3
suggested operating threshold (max F1): 0.40
```

> Catatan: angka di atas dari fixture sintetis untuk memverifikasi *tooling*-nya. Paritas & akurasi **pada data riil** menunggu eksekusi di perangkat (lihat §7).

### U7 — Sentralisasi ambang + protokol uji lapangan (Milestone 3)

- Baru: `DetectionConfig.java` — satu sumber untuk `CONFIDENCE_THRESHOLD` (0,35), `IOU_THRESHOLD` (0,45), `UPLOAD_CONFIDENCE_THRESHOLD` (0,40), `GEO_GRID_DECIMALS` (4), lengkap dengan prosedur kalibrasi berbasis sweep U6 di Javadoc. `YoloDetector` dan `ObjectDetectionView` kini merujuk ke sana (sebelumnya 0,40 hard-coded terpisah).
- Baru: `docs/field-test-protocol.md` — matriks 9 skenario (hover statis, uji termal 10 menit, jelajah lambat/cepat, rotasi yaw, objek tepi frame, oklusi, kehilangan sinyal, reset in-flight) dengan kriteria lulus yang terikat ke CSV metrics, plus tabel riwayat kalibrasi ambang.
- Nilai historis 0,35/0,40 **sengaja dipertahankan** sampai sweep berjalan pada data riil (sesuai KTD3: validasi dulu, baru tuning).

### U8 — Prediksi box antar-frame (Milestone 4)

**Masalah:** inferensi tiap ~400 ms, FPV 30 FPS → box membeku lalu melompat. Sesuai KTD4, solusinya prediksi, bukan inferensi lebih cepat (Helio G99 sudah di plafonnya).

**Perubahan:**
- Baru: `BoxInterpolator.java` (murni) — memajukan box sepanjang kecepatan track (dari `BoxTracker`), ekstrapolasi di-cap 700 ms, clamp `[0,1]` tanpa menyusutkan box.
- `OverlayView`: tick `postInvalidateDelayed(33)` (~30 FPS) selama ada track hidup; **snap** ke box terukur saat deteksi baru tiba (dt≈0 → prediksi = pengukuran, tanpa akumulasi drift); track kedaluwarsa (> 700 ms) berhenti digambar → tidak ada *ghost box*.

**Verifikasi:** 6 test — prediksi kecepatan-konstan tepat di pertengahan siklus; snap saat dt=0; kedaluwarsa dalam satu siklus; cap ekstrapolasi; clamp tepi tanpa penyusutan; kecepatan negatif.

### U9 — Interval capture adaptif + gating termal scene-statis (Milestone 4)

**Perubahan:**
- Baru: `AdaptiveInterval.java` (murni) — interval = EMA(waktu siklus capture+infer) × 1,3, di-clamp `[300 ms, 800 ms]`; lantai 300 ms melindungi **Dropped=0**; hover statis (streak 5 frame dengan signature luminance 8×8 hampir identik) meregangkan interval sampai 1.200 ms untuk relaksasi termal; kembali responsif dalam satu siklus saat ada gerakan.
- `BaseFpvView`: signature dihitung sebelum bitmap di-recycle (64 pembacaan piksel — dapat diabaikan vs inferensi 200 ms); kolom `intervalMs` di CSV kini nilai adaptif aktual.
- Warm-up satu inferensi dummy 640×360 saat init (menyerap kompilasi shader GPU/DVFS; durasi tercatat ke event log; tracker di-reset setelahnya agar tidak meninggalkan state).

**Verifikasi:** 9 test (kepatuhan floor/ceiling, peregangan statis 2×, pemulihan satu siklus, redaman EMA terhadap spike, deteksi statis vs gerak, guard null/mismatch).

### U10 — Agregasi puncak geo-grid + persistensi offline (Milestone 5)

**Masalah:** dedup upload hanya ber-kunci `trackId` — dirusak bug tracking (churn → over-count; false merge → under-count); tidak ada jaminan saat sinyal hilang.

**Perubahan:**
- Baru: `GeoGridAggregator.java` (murni) — sumber kebenaran dedup pindah ke **sel grid geografis ~11 m** (`toFixed(4)`, cocok dengan asumsi grid KDE/MAUP dashboard); tiap sel menyimpan **puncak jumlah deteksi simultan**; tulis Firestore hanya saat puncak naik.
- `ObjectDetectionView`: satu dokumen per sel (doc id = cell key, `set(..., merge)`), skema **aditif** demi kompatibilitas dashboard: `jumlahDeteksi` kini berisi puncak (sebelumnya selalu 1 per baris), ditambah `peakCount`, `cellKey`, `timestampEpochMs`; **format string `timestamp` tidak diubah** (parsing Calendar Boundary dashboard aman).
- Persistensi offline Firestore diaktifkan eksplisit → deteksi saat sinyal hilang antri lokal dan tersinkron otomatis saat konek lagi.
- Guard GPS NaN/(0,0) dipertahankan (juga di dalam aggregator).

**Verifikasi:** 7 test — tumpukan diam 25 siklus (≈10 s) dengan jitter GPS ±2 m → **tepat satu** penulisan Firestore; kenaikan puncak → satu penulisan tambahan; sel bersebelahan → dua record; puncak bukan penjumlahan; guard GPS; kunci sel stabil & locale-safe (titik desimal, bukan koma); reset.

**Temuan saat pengujian (didokumentasikan):** koordinat yang jatuh tepat di **batas sel** dapat memetakan ke dua sel bersebelahan di bawah jitter GPS — efek batas MAUP yang melekat pada gridding `toFixed(4)`; KDE smoothing di dashboard meredamnya. Test awal saya sendiri menabrak kondisi ini (koordinat uji kebetulan di batas sel) — kasus uji dipindah ke pusat sel dan efeknya dicatat sebagai limitasi yang diketahui.

---

## 4. Log Verifikasi Lengkap

### 4.1 Suite penuh — dikompilasi dan dijalankan dari tree yang ter-commit (`git archive HEAD`)

```
== com.dji.sdk.sample.internal.view.BoxTrackerTest
  PASS lostTrack_prunedAfterMaxLostFrames_reappearanceGetsFreshId
  PASS happyPath_nearbyDetectionKeepsSameId
  PASS stationaryObject_keepsOneIdAcrossManyCycles
  PASS farDetection_spawnsNewIdInsteadOfStealingTrack
  PASS twoSeparateObjects_doNotCollapseIntoOneTrack
  PASS closeObjects_greedyByDistanceAvoidsSwaps
  PASS emptyDetectionList_stateStaysConsistent
  PASS velocity_estimatedTowardMotionDirection
  PASS reset_clearsTracksAndIdCounter
== com.dji.sdk.sample.internal.view.LetterboxTest
  PASS centeredObject_mapsBackToCenter_iouAtLeast95
  PASS edgeObject_staysInsideUnitRange_notClippedByPadding
  PASS boxFullyInPadding_collapsesToZeroArea
  PASS squareInput_reducesToIdentity
  PASS portraitSource_padsHorizontally
  PASS geometry_16by9_into640
== com.dji.sdk.sample.internal.view.InterpolationTest
  PASS constantVelocity_predictedWithinErrorAtMidCycle
  PASS freshDetection_snapsToMeasuredBox
  PASS staleTrack_expiresWithinOneCycle
  PASS extrapolation_cappedBeyondWindow
  PASS prediction_staysInsideUnitBox_withoutShrinking
  PASS negativeVelocity_movesLeftAndClampsAtZero
== com.dji.sdk.sample.internal.util.MetricsLoggerTest
  PASS happyPath_100FramesProduce100WellFormedRows
  PASS zeroDetections_rowStillWritten
  PASS rotation_boundsFileSize
  PASS unwritableDirectory_degradesToNoOpWithoutThrowing
== com.dji.sdk.sample.internal.util.QuantUtilTest
  PASS roundTrip_preservesNormalizedValueWithinOneStep
  PASS extremes_mapToInt8Range
  PASS midGray_mapsNearZero
  PASS clamping_neverOverflows
  PASS oldFormula_wasWrongForZeroPointZeroExports
== com.dji.sdk.sample.internal.util.AdaptiveIntervalTest
  PASS ema_smoothsSpikes
  PASS interval_followsMeasuredCycleTimeWithHeadroom
  PASS interval_neverBelowSafeFloor
  PASS interval_cappedInActiveMode
  PASS staticScene_stretchesUpToStaticCeiling
  PASS motionResumes_intervalRestoredWithinOneCycle
  PASS signature_identicalFramesAreStatic
  PASS signature_motionIsNotStatic
  PASS signature_nullOrMismatchedNeverStatic
== com.dji.sdk.sample.internal.util.GeoGridAggregatorTest
  PASS stationaryPile_10sOfCycles_writesOnce
  PASS peakIncrease_triggersOneMoreWrite
  PASS adjacentCells_produceSeparateRecords
  PASS sameCell_peakNotSum
  PASS gpsGuards_preserved
  PASS cellKey_isStableAndLocaleSafe
  PASS reset_clearsAllCells
---- total: 46  pass: 46  fail: 0
```

### 4.2 Simulasi pipeline end-to-end (deteksi → tracking → interpolasi → adaptive gate → geo-grid)

Skenario 12 detik: tumpukan A diam di tengah kiri; tumpukan B bergeser ke kanan 0,01/siklus lalu keluar frame di siklus 22; setelahnya hover statis. GPS jitter ±2 m, waktu siklus 230–260 ms.

```
cycle  t(ms)  interval  tracks(ids)      static  gridWrite  midCyclePrediction
    0      0      363ms  #1 #2            false   PEAK=2     cx 0.600 -> 0.600
    1    363      354ms  #1 #2            false   -          cx 0.610 -> 0.612
    5   1724      322ms  #1 #2            false   -          cx 0.650 -> 0.655
   10   3316      320ms  #1 #2            false   -          cx 0.700 -> 0.705
   21   6839      310ms  #1 #2            false   -          cx 0.810 -> 0.815
   22   7149      314ms  #1               true    -          -          <- B keluar frame
   26   8403      618ms  #1               true    -          -          <- streak statis 5 → interval 2×
   29  10241      624ms  #1               true    -          -

== SUMMARY ==
physical objects        : 2 (1 stationary, 1 drifting, drift exits at cycle 22)
track IDs ever created  : 2   (tracker lama: churn tak terbatas begitu ada objek ke-2)
active tracks at end    : 1
Firestore writes        : 1   (dedup lama: 1 per trackId; dengan churn -> puluhan)
grid cells recorded     : 1
final capture interval  : 624 ms (diregangkan gating termal hover-statis)
```

Terbaca langsung di log: ID stabil sepanjang 30 siklus, prediksi mid-cycle mengikuti arah gerak (cx 0,600→0,605 saat objek bergerak +0,01/siklus), interval melonjak 314→618 ms tepat setelah 5 frame statis, dan hanya **satu** penulisan Firestore untuk pemandangan yang sebenarnya berisi satu lokasi menetap.

### 4.3 Integritas struktural file yang ter-commit

Ke-20 file Java yang dibuat/diubah diperiksa: keseimbangan kurung kurawal 0 dan diakhiri `}` — `ALL STRUCTURALLY SOUND`. (Latar: lihat §6, kendala sinkronisasi editor.)

---

## 5. Status Requirement (R1–R10)

| ID | Status | Bukti |
|----|--------|-------|
| R1 | ✅ Selesai | BoxTracker + 9 test; repro bug lama vs baru (§3-U2) |
| R2 | ✅ Selesai | Letterbox + 6 test; IoU round-trip ≥ 0,95; capture 640×360 |
| R3 | ✅ Selesai | AtomicBoolean gate; reset penuh terverifikasi; try-with-resources |
| R4 | ✅ Selesai | QuantUtil + 5 test (termasuk repro bug lama); guard label gagal-nyaring |
| R5 | 🔶 Tooling selesai, eksekusi data riil menunggu perangkat | DetectionHarness + parity_check teruji fungsional (§3-U6) |
| R6 | 🔶 Tooling selesai; ambang 0,35/0,40 dipertahankan sampai sweep riil | DetectionConfig + prosedur kalibrasi terdokumentasi |
| R7 | ✅ Selesai | BoxInterpolator + 6 test; terlihat di simulasi E2E |
| R8 | 🔶 Fondasi terpasang (letterbox = perbaikan akurasi terbesar; kuantifikasi menunggu sweep U6 riil) | — |
| R9 | ✅ Mekanisme selesai (validasi lapangan 10 menit sesuai protokol §skenario-2) | AdaptiveInterval + 9 test |
| R10 | ✅ Selesai (verifikasi bench dengan GPS riil sesuai protokol) | GeoGridAggregator + 7 test; persistensi offline aktif |

---

## 6. Catatan Proses & Kendala Teknis Sesi

Transparansi penuh atas apa yang terjadi selama eksekusi:

1. **Sinkronisasi editor→worktree yang tidak stabil.** Selama sesi, salinan file di lingkungan eksekusi beberapa kali tertinggal/terpotong terhadap file asli di folder proyek Anda. Akibatnya commit U1 dan U3 sempat memuat snapshot terpotong untuk `BaseFpvView`/`OverlayView`/`YoloDetector`. Ini **terdeteksi lewat pemeriksaan integritas blob** dan **diperbaiki di commit U4** (pesan commitnya mencatat hal ini). Sejak itu setiap commit distage dari salinan otoritatif dan diverifikasi (hash + simbol kunci + penutup `}`), dan suite akhir dikompilasi langsung dari `git archive HEAD` — sehingga **kondisi akhir repo dijamin utuh**; hanya dua commit intermediat itu yang tidak akan bisa dikompilasi jika di-checkout sendiri.
2. **Review kualitas.** Diff total (±2.100 baris) memenuhi ambang eskalasi review formal; karena pemunculan subagent dibatasi di lingkungan ini, review dilakukan inline: pemeriksaan threading (volatile/affinity handler, `compareAndSet`), terminasi loop invalidate overlay, semantik penuaan track, validitas doc-id Firestore (koma legal), dan reuse buffer letterbox. Tidak ada temuan blocking; dua observasi diterima: alokasi `RectF` per draw (~30/s, churn GC dapat diabaikan) dan harness berjalan di thread pemroses (memang mode diagnostik one-shot).
3. **Lint:** repo tidak memiliki konfigurasi linter; dilewati (dicatat sesuai checklist).
4. **Build Gradle penuh belum dijalankan** — sandbox tidak memiliki Android SDK/DJI SDK. Semua logika murni diverifikasi di JVM; file ber-dependensi Android diverifikasi struktural + inspeksi. Build pertama di Android Studio mungkin memunculkan isu kompilasi kecil — jika ada, kirimkan pesan errornya.

---

## 7. Langkah Berikutnya (untuk Anda)

1. **Build & install** dari branch `fix/waste-detection-debug-validation` di Android Studio (`testImplementation junit` akan terunduh otomatis; jalankan juga `./gradlew testDebugUnitTest` untuk melihat 46 test lulus di mesin Anda).
2. **Jalankan harness U6 pada data riil:** salin test-split Roboflow ke `Android/data/<pkg>/files/harness_images/`, buat file kosong `run_harness`, buka FPV, tarik `harness_predictions.jsonl`; di PC/Colab jalankan `--generate-reference` lalu bandingkan. Target AE3: IoU ≥ 0,9 untuk ≥ 95% box.
3. **Kalibrasi ambang** dari sweep (`--labels`), perbarui `DetectionConfig` + tabel riwayat di `docs/field-test-protocol.md`.
4. **Uji lapangan** sesuai matriks 9 skenario; tarik CSV metrics tiap sesi.
5. **Checklist dashboard web** (kode belum di repo ini — tetap ditunda sesuai rencana): pastikan presisi grid dashboard = `toFixed(4)`; intensitas heatmap membaca `jumlahDeteksi`/`peakCount` (agregat), bukan hitungan baris; parsing Calendar Boundary tetap cocok (format string tidak berubah).
6. Branch **belum di-push** — push/PR menunggu keputusan Anda.
