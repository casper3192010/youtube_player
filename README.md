# YT Long Novel Player

A specialized YouTube player for long-form content (audiobooks, novels).

## Features
- **Auto Resume**: Automatically saves progress every 10s and on pause/exit. Resumes exactly where you left off.
- **Audio-Focused UI**: Large Play/Pause buttons, -15s / +30s seek buttons.
- **Speed Control**: 0.75x - 2.0x support.
- **PiP Mode**: Background listening support via Android Picture-in-Picture.

## Technologies
- Android Native (Kotlin)
- [Android-YouTube-Player](https://github.com/PierfrancescoSoffritti/android-youtube-player)

## How to Run
1. Open **Android Studio**.
2. Select **Open**.
3. Navigate to `c:/Users/tommy_wu/.gemini/antigravity/scratch/youtube_player` and click OK.
4. Wait for Gradle Sync to complete.
5. Connect an Android device (or Emulator).
6. Click **Run**.

## Test Case
Video: [5MFURu4dwPE](https://www.youtube.com/watch?v=5MFURu4dwPE)
Length: 7+ hours.
Test: Play to > 04:00:00, close app, reopen. Should resume at 4h mark.
