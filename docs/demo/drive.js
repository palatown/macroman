// Plays the README demo on the emulator (432x960). Writes the model-wait spans to cuts.json so they can be squeezed.
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const adbPath = process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : "adb";
const [framesDir, cutsOut, text] = process.argv.slice(2);
const adb = (...a) => execFileSync(adbPath, ["-s", "emulator-5554", ...a], { encoding: "utf8", env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
const sleep = (ms) => Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
const tap = (x, y) => adb("shell", "input", "tap", String(Math.round(x)), String(Math.round(y)));
const texts = () => {
  adb("shell", "uiautomator", "dump", "/sdcard/ui.xml");
  return [...adb("shell", "cat", "/sdcard/ui.xml").matchAll(/ text="([^"]*)"/g)].map((m) => m[1]);
};
function until(cond, timeoutMs) {
  for (const t = Date.now(); Date.now() - t < timeoutMs; sleep(700)) if (cond(texts())) return;
  throw new Error("timed out");
}

const t0 = Math.min(...fs.readdirSync(framesDir).filter((f) => /^\d+\.png$/.test(f)).map((f) => parseInt(f)));
const rel = () => Date.now() - t0;
const cuts = [];

// Fixed spots on a 432x960 screen; only the model waits look at the UI.
sleep(1500);
tap(100, 812); // input field
sleep(600);
const words = text.split(" ");
words.forEach((w, i) => adb("shell", "input", "text", w + (i < words.length - 1 ? "%s" : "")));
sleep(500);
tap(403, 574); // send
let from = rel() + 900;
until((t) => t.some((x) => /^Log \d+ items?$/.test(x)), 240000);
cuts.push([from, rel() - 300, 1400]);
sleep(1800);

// First item's portion slider, 1x to 1.5x (0.5x..2x in 0.25 steps).
const y = 359, left = 29.6, step = 51.2;
adb("shell", "input", "swipe", String(left + 2 * step), String(y), String(left + 4 * step), String(y), "700");
from = rel() + 600;
sleep(700);
// The amount reads "… ×1.5" until the quick model's rewrite lands.
until((t) => !t.some((x) => / ×\d/.test(x)), 90000);
cuts.push([from, rel() - 200, 900]);
sleep(1800);

tap(351, 896); // Log N items
sleep(2400);
tap(40, 484); // photo thumbnail
sleep(2400);
tap(216, 480); // close
sleep(1500);

fs.writeFileSync(cutsOut, JSON.stringify(cuts));
console.log("cuts", JSON.stringify(cuts));
