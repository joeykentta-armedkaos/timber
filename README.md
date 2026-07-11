# Timbre for Android

Select text anywhere on your phone and hear it read aloud — or paste
anything into the app. Uses Android's built-in on-device voices, so it
is free, works offline, and needs no server.

What you get:

- **The floating bubble** — enable it once (Settings → Accessibility →
  Timbre bubble, or the switch in the app) and a small draggable bubble
  floats over every app. **Hold the bubble and select text with another
  finger** — it reads the selection aloud as you select; **let go to
  stop**. Tap the bubble to read whatever is currently selected. Drag it
  anywhere; drag it onto the X to hide it. Note: a few apps hide their
  text from accessibility services — for those, use the selection-menu
  path below.
- **"Read with Timbre" everywhere** — select text in any app, tap the
  three-dot ⋮ menu in the selection toolbar, choose *Read with Timbre*.
  It opens and starts reading immediately. (Sharing text to Timbre works
  too.)
- **Follow-along highlighting** — the sentence being read gets a soft
  amber tint, the current word a stronger one, and the view scrolls
  along.
- **Simple controls** — big play/pause, stop, back-one-sentence, a
  seekable progress bar, voice picker, and speed & pitch.
- Phone and tablet layouts.

## Get the APK (no coding needed)

One-time setup (~10 minutes, on a computer):

1. Create a free GitHub account if you don't have one, and make a new
   repository (Private is fine).
2. Upload this folder's contents to it (drag-and-drop onto the repo page
   works: "uploading an existing file").
3. GitHub automatically builds the app (takes a few minutes — watch the
   **Actions** tab turn green).

Then, on your phone — this is the easy part:

4. Open your repo's **Releases** page in the phone's browser
   (`github.com/YOURNAME/YOURREPO/releases`) and tap **timbre.apk**
   under the newest build.
5. Android asks once for permission to install apps from your browser —
   allow it, then tap **Install**. Done.

Every future update is just: change lands in the repo → a new Release
appears a few minutes later → tap the new `timbre.apk` on your phone.
Tip: the free app **Obtainium** (from the Play Store alternative
F-Droid, or its own GitHub) can watch your repo's Releases and
install updates for you automatically — true one-tap updates.

## Or build with Android Studio

1. Install [Android Studio](https://developer.android.com/studio).
2. Open this folder (File → Open). Accept any prompts to sync/generate
   the Gradle wrapper.
3. Plug in your phone (enable USB debugging) and press **Run**, or use
   **Build → Build APK(s)** and sideload the result.

## Voice quality tip

The app uses whatever text-to-speech voices are installed. On most
phones that's Google's Speech Services — for the best sound, install or
update **Speech Recognition & Synthesis from Google** on the Play Store,
then in Timbre tap the voice chip and try the different voices. Higher
quality variants download automatically once selected in
Settings → Accessibility → Text-to-speech output.
