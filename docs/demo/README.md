# Recording the demo

`docs/demo.png` is an animated PNG recorded from an Android emulator. GitHub plays it like a GIF. This is how it
was made, roughly, in case it needs redoing after the UI changes.

1. Create and boot an emulator (API 35, Pixel 7 profile) and install the debug build.
2. Point the app at a model. The demo used Ollama on the host, which the emulator reaches at
   `http://10.0.2.2:11434/v1`.
3. Fill the database with a week of meals: pull `databases/macroman.db` with `adb exec-out run-as`, run
   `node seed.js macroman.db <photo-name>.jpg`, and push it back along with a label "photo" in
   `no_backup/photos/` (rendered from `label.html` with headless Edge).
4. Set the clock to lunchtime (`adb root`, `date`) so the suggestions make sense, shrink the screen to 432x960
   (`wm size`, `wm density 168`) to keep the file small, and turn on the status bar's demo mode for a fixed clock.
5. Start `node capture.js frames/` in the background, then `node drive.js frames/ cuts.json "<meal text>"`, which
   types, taps and drags at fixed screen positions and notes when it's waiting on the model.
6. `node apng.js frames/ demo.png cuts.json` stitches the screenshots into an APNG. It drops duplicate frames and
   speeds up the model waits listed in `cuts.json`.

`apng.js` reuses each screenshot's compressed image data rather than decoding it, so it needs nothing beyond Node.
