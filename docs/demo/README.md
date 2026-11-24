# Recording the demo

`docs/demo.png` is an animated PNG recorded from an Android emulator. GitHub plays it like a GIF. This is how it
was made, roughly, in case it needs redoing after the UI changes.

1. Create and boot an emulator (API 35, Pixel 7 profile) and install the debug build. Set `ANDROID_HOME`, and
   `ANDROID_SERIAL` to that emulator (e.g. `emulator-5556`). `drive.js` refuses to send input unless the
   emulator's AVD is named `macroman_demo` (or whatever `DEMO_AVD` says), in case other emulators are running.
2. Point the app at a model. The demo used Ollama on the host with `gemma4:26b-a4b`, which the emulator reaches
   at `http://10.0.2.2:11434/v1`. Make one request first so the model is loaded.
3. Fill the database with a week of meals: pull `databases/macroman.db` with `adb exec-out run-as` (checkpoint
   the WAL first, or the copy is empty), run `node seed.js macroman.db <photo-name>.jpg`, and push it back along
   with a label "photo" in `no_backup/photos/` (rendered from `label.html` with headless Edge at 420x488).
4. Set the clock to lunchtime on the seeded day (`adb root`, `settings put global auto_time 0`,
   `date 111612202026.00`) so the suggestions make sense, shrink the screen to 432x960 (`wm size`,
   `wm density 168`) to keep the file small, and turn on the status bar's demo mode for a fixed clock.
5. Turn off Gboard's clipboard suggestions (Gboard settings → Clipboard). The emulator shares the host's
   clipboard, and the keyboard otherwise shows whatever was last copied on the computer.
6. Start `node capture.js frames/` in the background, then
   `node drive.js frames/ cuts.json "two slices of pepperoni pizza and a diet coke" "almnds" "Almonds"`, which
   types the meal, moves the first portion slider to 1.5x, logs it, re-logs the third argument from the history
   search, and opens the label photo. It notes when it's waiting on the model. Touch `frames/stop` when it's done.
7. `node apng.js frames/ demo.png cuts.json` stitches the screenshots into an APNG. It drops duplicate frames,
   speeds up the model waits listed in `cuts.json`, and caps any still frame at 2.2 s.

`apng.js` reuses each screenshot's compressed image data rather than decoding it, so it needs nothing beyond Node.
