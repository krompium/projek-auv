# Field Test Protocol — UAV Waste Detection (U7)

Protokol uji lapangan yang dapat diulang untuk memvalidasi deteksi, tracking,
dan integritas data Firebase setelah perubahan apa pun pada pipeline.
Semua diagnostik berbasis file (USB dipakai RC — Logcat tidak praktis):
tarik `Android/data/<pkg>/files/metrics/detection_metrics.csv` dan
`detection_events.log` setelah setiap sesi.

## Prasyarat

- Baterai drone & RC penuh; cuaca cerah, angin < 20 km/j.
- GPS lock ≥ 6 satelit (tunggu 30–60 detik di luar ruangan sebelum mulai).
- Bersihkan/arsipkan CSV metrics dari sesi sebelumnya.
- Siapkan objek uji: 3–5 tumpukan sampah dummy (botol/plastik) dengan jarak
  antar-tumpukan > 15 m (melebihi satu sel geo-grid ~11 m) dan satu pasangan
  tumpukan berjarak < 5 m (dalam satu sel).

## Matriks Uji

| # | Skenario | Parameter | Lulus jika |
|---|----------|-----------|------------|
| 1 | Hover statis di atas 1 tumpukan | Alt 5 m, 60 s | `activeTrackCount` stabil ≈1; `totalTrackIds` tidak menanjak; 1 dokumen Firestore per sel |
| 2 | Hover statis — uji termal | Alt 5 m, 10 menit | `inferMs` tidak menanjak > 20% dari menit ke-1 ke menit ke-10; Dropped tetap 0 |
| 3 | Jelajah lambat melintasi 3+ tumpukan | Alt 8 m, 2 m/s | Setiap tumpukan mendapat trackId berbeda; box mengikuti objek tanpa freeze-jump |
| 4 | Jelajah cepat | Alt 8 m, 5 m/s | Tidak ada ID churn masif; recall boleh turun namun dicatat |
| 5 | Rotasi yaw di tempat | Alt 8 m, 30°/s | Track ID bertahan selama objek tetap di frame |
| 6 | Objek di tepi frame | Alt 8 m | Box menempel objek di tepi kiri/kanan FPV (validasi letterbox R2) |
| 7 | Oklusi sementara (lewat balik pohon) | Alt 8 m | ID sama jika muncul kembali ≤ 5 siklus; ID baru jika lebih lama (sesuai `MAX_LOST_FRAMES`) |
| 8 | Sinyal hilang (matikan data seluler 60 s) | — | Deteksi selama offline tersinkron ke Firestore setelah reconnect; tidak ada data hilang (R10) |
| 9 | Reset tracker in-flight | tombol reset | `totalTrackIds` mulai lagi dari 1 (event `TRACKER reset` tercatat) |

## Ambang (threshold) — cara kalibrasi

1. Jalankan harness U6 pada test-split Roboflow (`run_harness` marker).
2. `python3 tools/parity_check.py --device harness_predictions.jsonl --labels <gt> --sweep 0.20:0.60:0.05`
3. Set `DetectionConfig.CONFIDENCE_THRESHOLD` = ambang F1-maks;
   `UPLOAD_CONFIDENCE_THRESHOLD` ≥ ambang detektor.
4. Catat tabel sweep di bawah ini.

### Riwayat kalibrasi

| Tanggal | Dataset | Ambang terpilih | Precision | Recall | Catatan |
|---------|---------|-----------------|-----------|--------|---------|
| (belum dijalankan pada data riil — nilai historis 0.35/0.40 dipertahankan) | | | | | |

## Analisis pasca-penerbangan

1. Buka `detection_metrics.csv` di spreadsheet.
2. Periksa: `droppedCount` konstan 0; `inferMs` p50/p95; rasio
   `totalTrackIds` : jumlah objek fisik (idealnya ≈ 1 : 1).
3. Bandingkan jumlah dokumen `waste_detections_v2` dengan jumlah tumpukan
   fisik; selisih = over/under-count yang harus dijelaskan.
4. Simpan CSV + skrinsot heatmap dashboard ke arsip sesi.
