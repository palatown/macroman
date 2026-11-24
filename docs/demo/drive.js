// Plays the README demo on the emulator (432x960): log a meal by text, re-log one from the history search, open a
// label photo. Usage: node drive.js frames/ cuts.json "<meal>" "<search text>" "<result to tap>".
// Writes the model-wait spans to cuts.json so they can be squeezed.
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const adbPath = process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : "adb";
const serial = process.env.ANDROID_SERIAL;
if (!serial) throw new Error("Set ANDROID_SERIAL to the demo emulator, e.g. emulator-5556");
const [framesDir, cutsOut, text, search, pick] = process.argv.slice(2);
const adb = (...a) => execFileSync(adbPath, ["-s", serial, ...a], { encoding: "utf8", env: { ...process.env, MSYS_NO_PATHCONV: "1" } });
// Other emulators may be running; only ever send input to the demo one.
const avd = adb("emu", "avd", "name").split(/\r?\n/)[0].trim();
if (avd !== (process.env.DEMO_AVD || "macroman_demo")) throw new Error(`${serial} is ${avd}, not the demo emulator`);
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

// Taps go to elements found on screen; only the send button is at a fixed spot on the 432x960 screen.
const nodes = () => {
  adb("shell", "uiautomator", "dump", "/sdcard/ui.xml");
  return [...adb("shell", "cat", "/sdcard/ui.xml").matchAll(/<node [^>]*>/g)].map(([n]) => {
    const [x1, y1, x2, y2] = n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/).slice(1).map(Number);
    return {
      text: n.match(/ text="([^"]*)"/)[1], desc: n.match(/content-desc="([^"]*)"/)[1], cls: n.match(/class="([^"]*)"/)[1],
      x1, x2, x: (x1 + x2) / 2, y: (y1 + y2) / 2,
    };
  });
};
const tapOn = (label, below = 0) => {
  const n = nodes().find((n) => (n.text === label || n.desc === label) && n.y > below);
  if (!n) throw new Error(`not on screen: ${label}`);
  tap(n.x, n.y);
};
const type = (s) => s.split(" ").forEach((w, i, all) => adb("shell", "input", "text", w + (i < all.length - 1 ? "%s" : "")));

sleep(1500);
type(text); // the food field has focus when the app opens
sleep(500);
tap(403, 574); // send
let from = rel() + 900;
until((t) => t.some((x) => /^Log \d+ items?$/.test(x)), 240000);
cuts.push([from, rel() - 300, 1400]);
sleep(1800);

// First item's portion slider, 1x to 1.5x. Its seven stops (0.5x..2x by 0.25) run from 18 px inside the left
// edge to 21 px inside the right.
const bar = nodes().find((n) => n.cls === "android.widget.SeekBar");
const left = bar.x1 + 18, step = (bar.x2 - 21 - left) / 6;
adb("shell", "input", "swipe", String(left + 2 * step), String(bar.y), String(left + 4 * step), String(bar.y), "700");
from = rel() + 600;
sleep(700);
// The amount reads "… ×1.5" until the quick model's rewrite lands.
until((t) => !t.some((x) => / ×\d/.test(x)), 90000);
cuts.push([from, rel() - 200, 900]);
sleep(1800);
tapOn(nodes().map((n) => n.text).find((x) => /^Log \d+ items?$/.test(x)));
sleep(2600);

// Re-log something from history, typed sloppily.
tapOn("Search past food");
sleep(1200);
type(search);
sleep(1800);
tapOn(pick, 300);
sleep(2600);

// The label photo a bar was logged from.
tapOn("Food photo");
sleep(2400);
tap(216, 480); // close
sleep(1500);

fs.writeFileSync(cutsOut, JSON.stringify(cuts));
console.log("cuts", JSON.stringify(cuts));
