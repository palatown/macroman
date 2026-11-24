// Stitches timestamped PNG screenshots into an animated PNG without decoding them: every frame has the same
// IHDR, so each frame's IDAT data can be reused as-is (as IDAT for frame 0, fdAT after that).
// Usage: node apng.js <framesDir> <out.png> [cuts.json]
// cuts.json lists [fromMs, toMs, keepMs] spans (relative to the first frame) to squeeze, e.g. waiting on the model.
const fs = require("node:fs");
const path = require("node:path");
const zlib = require("node:zlib");
const crypto = require("node:crypto");

const [dir, outFile, cutsFile] = process.argv.slice(2);
const cuts = cutsFile ? JSON.parse(fs.readFileSync(cutsFile, "utf8")) : [];

function chunks(buf) {
  const list = [];
  for (let i = 8; i < buf.length; ) {
    const len = buf.readUInt32BE(i);
    const type = buf.toString("ascii", i + 4, i + 8);
    list.push({ type, data: buf.subarray(i + 8, i + 8 + len) });
    i += 12 + len;
  }
  return list;
}

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, "ascii");
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(zlib.crc32(Buffer.concat([Buffer.from(type, "ascii"), data])) >>> 0, 0);
  return Buffer.concat([head, data, crc]);
}

const files = fs.readdirSync(dir).filter((f) => /^\d+\.png$/.test(f)).sort((a, b) => parseInt(a) - parseInt(b));
const t0 = parseInt(files[0]);

// Map capture time to playback time: squeezed spans play back in keepMs.
function playback(t) {
  let shift = 0;
  for (const [from, to, keep] of cuts) {
    if (t >= to) shift += to - from - keep;
    else if (t > from) return from - shift + ((t - from) * keep) / (to - from);
  }
  return t - shift;
}

// Collapse identical consecutive frames; each frame lasts until the next distinct one.
const frames = [];
for (const f of files) {
  const buf = fs.readFileSync(path.join(dir, f));
  const cs = chunks(buf);
  // screencap compresses for speed; the same filtered scanlines deflate a good deal smaller at level 9.
  const raw = zlib.inflateSync(Buffer.concat(cs.filter((c) => c.type === "IDAT").map((c) => c.data)));
  const idat = zlib.deflateSync(raw, { level: 9, memLevel: 9 });
  const hash = crypto.createHash("sha1").update(idat).digest("hex");
  const at = playback(parseInt(f) - t0);
  if (frames.length && frames[frames.length - 1].hash === hash) continue;
  frames.push({ ihdr: cs.find((c) => c.type === "IHDR").data, idat, hash, at });
}
const end = playback(parseInt(files[files.length - 1]) - t0) + 300;
// Tiny frames from squeezed spans are merged into their neighbour so the animation doesn't stutter.
const kept = frames.filter((f, i) => i === 0 || (frames[i + 1]?.at ?? end) - f.at >= 60);

const parts = [Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", kept[0].ihdr)];
const actl = Buffer.alloc(8);
actl.writeUInt32BE(kept.length, 0);
actl.writeUInt32BE(0, 4); // loop forever
parts.push(chunk("acTL", actl));
const w = kept[0].ihdr.readUInt32BE(0), h = kept[0].ihdr.readUInt32BE(4);
let seq = 0;
// Nothing on screen holds longer than this; the driver's pauses to find things on screen would otherwise drag.
const MAX_HOLD_MS = 2200;
let total = 0;
kept.forEach((f, i) => {
  const ms = Math.min(MAX_HOLD_MS, Math.max(20, Math.round((kept[i + 1]?.at ?? end) - f.at)));
  total += ms;
  const fctl = Buffer.alloc(26);
  fctl.writeUInt32BE(seq++, 0);
  fctl.writeUInt32BE(w, 4);
  fctl.writeUInt32BE(h, 8);
  fctl.writeUInt32BE(0, 12);
  fctl.writeUInt32BE(0, 16);
  fctl.writeUInt16BE(Math.min(ms, 65535), 20);
  fctl.writeUInt16BE(1000, 22);
  fctl[24] = 0; // dispose: none
  fctl[25] = 0; // blend: source
  parts.push(chunk("fcTL", fctl));
  if (i === 0) parts.push(chunk("IDAT", f.idat));
  else {
    const s = Buffer.alloc(4);
    s.writeUInt32BE(seq++, 0);
    parts.push(chunk("fdAT", Buffer.concat([s, f.idat])));
  }
});
parts.push(chunk("IEND", Buffer.alloc(0)));
const out = Buffer.concat(parts);
fs.writeFileSync(outFile, out);
console.log(`${kept.length} frames, ${(total / 1000).toFixed(1)} s, ${(out.length / 1e6).toFixed(2)} MB`);
