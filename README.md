# SiliCaTool

Android NFC utility for reading FeliCa cards and writing selected data into SiliCa.

## Summary

SiliCaTool is a Jetpack Compose Android app that reads FeliCa cards, lets you pick up to four systems and services independently, previews block data, and writes an image to a SiliCa tag. Service blocks are trimmed or zero-padded to 12 blocks, and IDm/PMm, system codes, and service codes are written alongside the data.

## Features

- Discover FeliCa systems and services; list services grouped by system even if no system is selected.
- Select up to four systems and four services; choose one service as the write source.
- View block contents with Shift_JIS decoding and block-by-block hex display.
- Write to SiliCa with 12-block limit handling (trim or zero-pad) and IDm/PMm preservation.
- Guided flow: read → select → confirm → write, with error/status messaging.

## Requirements

### Hardware
- Android device with NFC-F (FeliCa) support
- FeliCa card to read
- SiliCa tag to write

### Software
- Android Studio (Hedgehog or newer)
- Android SDK 36, build-tools for SDK 36
- JDK 11+

## Build & Install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run the `app` module on an NFC-capable device.

## Usage

1. Launch the app and tap “FeliCa を読み取る,” then present the FeliCa card.
2. Choose systems (optional) and services (required for writing); pick the service whose blocks will be written.
3. Review the summary; if a service has more than 12 blocks you can trim to the first 12, otherwise blocks are zero-padded to 12.
4. Tap “SiliCa に書き込む” and present the SiliCa tag to write IDm/PMm, system codes, service codes, and blocks.

## Project Structure

- `app/src/main/java/ws/nyaa/silicatool/MainActivity.kt`: UI flow and selection logic.
- `app/src/main/java/ws/nyaa/silicatool/FelicaClient.kt`: Low-level FeliCa read/write logic and framing.
- `app/src/main/java/ws/nyaa/silicatool/FelicaModels.kt`: Data models and helpers.

## License

[MIT](https://opensource.org/licenses/MIT)

Copyright (c) 2025 KIRISHIKI Yudai
