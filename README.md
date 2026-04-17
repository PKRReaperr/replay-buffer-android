# Replay Buffer Android

Android Studio project for a replay-buffer style camera app.

## What it does

- Shows a live camera preview with CameraX.
- Starts idle and only records when the user taps the record button.
- Continuously records short rolling segments while recording is enabled.
- Keeps the latest five minutes of footage in the app cache.
- Lets the user choose a replay length between 10 seconds and 5 minutes.
- Exposes camera-style controls for zoom, exposure, torch, and shutter presets.
- Shows a live progress bar for the current 5-second buffer segment.
- When the save button is pressed, it closes the current segment, combines the newest matching segments, and saves the exported clip to `Movies/ReplayBuffer`.

## Test on Windows

1. Open this repo folder in Android Studio.
2. Wait for Gradle sync to finish.
3. If Android Studio asks for SDK components, install them.
4. Run the app on:
   - an Android phone connected over USB, or
   - an Android emulator
5. Grant camera and microphone permissions.
6. Tap the record button to start buffering.
7. Wait for footage to buffer, then tap the replay button or save button.

## Notes

- The Android replay export is based on 5-second segments, so saved clips are coarse to the nearest segment boundary.
- Manual shutter presets are best-effort and may behave differently depending on the device camera driver.
- I could not run an Android build in this environment, so you should expect Android Studio to be the first real compile check.
