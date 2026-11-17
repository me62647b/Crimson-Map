# Crimson Desert Map – Android app (personal use)

Builds an APK with GitHub Actions. Keep this repository **private** (it contains map data that is for personal use only, and the app's signing key).

## Build
1. Create a new **private** repository on GitHub.
2. Upload everything in this folder, including the hidden `.github` folder (see "Uploading" below).
3. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push (or click **Run workflow**).
4. When it finishes (green tick), open the run and download **CrimsonDesertMap-apk** under *Artifacts*. Unzip it to get the `.apk`.
5. Copy the APK to your phone/tablet and install it (allow "Install unknown apps" for your file manager or browser when asked).

## Uploading
- Easiest: GitHub Desktop or `git` — add the folder as a repository and push.
- Web upload: drag the folder contents onto "uploading an existing file". If the `.github` folder doesn't come along,
  create it by hand: **Add file → Create new file**, name it `.github/workflows/build.yml`, and paste the contents of that file.

## Using it
- All your devices share **one 6-digit sync code**. Each device shows its current code in the sidebar (☰).
- To pair, open the app on both devices (same Wi-Fi), then on the new device: **Pair with device** → pick the other device → enter the code shown on it. The new device switches to that code.
- Easiest setup: pair the phone and the tablet with the **PC app's code**. Then any two of them sync whenever they're open on the same Wi-Fi — phone ↔ tablet works without the PC.
- Sync runs automatically when the app opens, every minute while it's open, and a few seconds after each change.
- **Get HD tiles** copies the high-detail map tiles over Wi-Fi from whichever paired device has the most (the PC, or another Android device that already has them).
- The app only syncs while it's open on screen (Android pauses it in the background).

## Updating the map
Replace `app/src/main/assets/index.html` with a newer map build and push. The signing key in `app/cdmap.keystore`
stays the same, so new APKs install over the old one and keep your progress.
