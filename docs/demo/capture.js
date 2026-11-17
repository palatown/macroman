// Grabs emulator screenshots as fast as adb allows until a "stop" file appears; names are capture times in ms.
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const path = require("node:path");
const adb = process.env.ANDROID_HOME ? `${process.env.ANDROID_HOME}/platform-tools/adb` : "adb";
const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
const stop = path.join(out, "stop");
if (fs.existsSync(stop)) fs.unlinkSync(stop);
let n = 0;
while (!fs.existsSync(stop)) {
  const t = Date.now();
  const png = execFileSync(adb, ["-s", "emulator-5554", "exec-out", "screencap", "-p"], { maxBuffer: 64 << 20 });
  fs.writeFileSync(path.join(out, `${t}.png`), png);
  n++;
}
console.log(`captured ${n} frames`);
