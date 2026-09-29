# Local Meme Search fork

Base: [rb-tyz/wheres-my-meme](https://github.com/rb-tyz/wheres-my-meme), MIT.
Pinned upstream commit: `168a6630d65f4a5a0d9fc03a7bab7d4aef85b6b5`.

This local fork retains the original Kotlin core, normalization/search tests,
bounded bitmap/EXIF loader, resources, Gradle wrapper and MIT attribution.
It replaces the View Activity, SQLiteOpenHelper and foreground Service with
Jetpack Compose, Room/Paging and a persistent WorkManager queue. It adds
bundled Latin OCR, Android 14 partial access, generation-based media deltas,
literal SQL matching, URI preview/sharing and production-path device tests.

The original regex/album selection UI is intentionally absent from this MVP.
The application ID `com.memeocr.local` allows installation beside upstream.

Upstream documentation and scripts archived under `docs/upstream/` describe upstream behavior
and historical tests. Current behavior is documented in README.md;
current measured results are in TEST-REPORT.md. Historical claims are not
evidence that this fork passed a test.
