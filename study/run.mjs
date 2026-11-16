// Runs the macroman food prompt against local Ollama models and scores the totals.
// Usage: node study/run.mjs <model[:think]> [more models...]   e.g. gemma4:12b gemma4:12b+think
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const OLLAMA = process.env.OLLAMA_URL ?? 'http://127.0.0.1:11434';
const cases = JSON.parse(readFileSync(resolve(here, 'cases.json'), 'utf8'));
const only = process.env.ONLY; // "text" or "image"

// Pull the system prompt straight from the app so the study tests what the app sends.
const kt = readFileSync(resolve(here, '../app/src/main/java/com/dirkraft/macroman/ai/MacroAi.kt'), 'utf8');
const SYSTEM = kt.match(/val FOOD_SYSTEM = """([\s\S]*?)"""/)[1].split('\n').map((l) => l.trim()).filter(Boolean).join('\n');

const item = {
  type: 'object',
  properties: Object.fromEntries(
    [['name', 'string'], ['quantity', 'string'], ['calories', 'number'], ['protein_g', 'number'], ['carbs_g', 'number'], ['fat_g', 'number'], ['fiber_g', 'number']]
      .map(([k, t]) => [k, { type: t }]),
  ),
  required: ['name', 'quantity', 'calories', 'protein_g', 'carbs_g', 'fat_g', 'fiber_g'],
};
const SCHEMA = { type: 'object', properties: { items: { type: 'array', items: item } }, required: ['items'] };

async function ask(model, think, c) {
  const msg = { role: 'user', content: c.input || 'Log what is in this photo.' };
  if (c.image) msg.images = [readFileSync(resolve(here, c.image)).toString('base64')];
  const body = {
    model, stream: false, format: SCHEMA, keep_alive: '10m',
    messages: [{ role: 'system', content: SYSTEM }, msg],
    options: { temperature: 0, num_ctx: 8192 },
  };
  if (think !== undefined) body.think = think;
  const t0 = performance.now();
  const r = await fetch(`${OLLAMA}/api/chat`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
  const ms = performance.now() - t0;
  const j = await r.json();
  if (!r.ok) throw new Error(j.error ?? r.statusText);
  return { ms, raw: j.message.content, thinking: j.message.thinking, evalCount: j.eval_count };
}

const sum = (items) => items.reduce((a, i) => ({
  kcal: a.kcal + (+i.calories || 0), p: a.p + (+i.protein_g || 0), c: a.c + (+i.carbs_g || 0),
  f: a.f + (+i.fat_g || 0), fiber: a.fiber + (+i.fiber_g || 0),
}), { kcal: 0, p: 0, c: 0, f: 0, fiber: 0 });

// Label cases must match the label; text cases are judged against a typical reference.
function score(c, got, n) {
  if (c.expectEmpty) return { pass: n === 0, kcalErrPct: n === 0 ? 0 : 100 };
  const kcalErrPct = Math.abs(got.kcal - c.ref.kcal) / c.ref.kcal * 100;
  const macroErr = Math.max(...['p', 'c', 'f'].map((k) => Math.abs(got[k] - c.ref[k])));
  const pass = c.exact
    ? kcalErrPct <= 5 && ['p', 'c', 'f', 'fiber'].every((k) => Math.abs(got[k] - c.ref[k]) <= Math.max(1.5, c.ref[k] * 0.05))
    : kcalErrPct <= 15 && ['p', 'c', 'f'].every((k) => Math.abs(got[k] - c.ref[k]) <= Math.max(4, c.ref[k] * 0.2));
  return { pass, kcalErrPct, macroErr };
}

const r1 = (x) => Math.round(x * 10) / 10;
mkdirSync(resolve(here, 'results'), { recursive: true });

for (const spec of process.argv.slice(2)) {
  const [model, flag] = spec.split('+');
  const think = flag === 'think' ? true : flag === 'nothink' ? false : undefined;
  const all = [...(only === 'image' ? [] : cases.text.map((c) => ({ ...c, kind: 'text' }))), ...(only === 'text' ? [] : cases.image.map((c) => ({ ...c, kind: 'image' })))];
  const rows = [];
  console.log(`\n=== ${spec} ===`);
  for (const c of all) {
    let row = { id: c.id, kind: c.kind, ref: c.ref, expectEmpty: c.expectEmpty };
    try {
      const a = await ask(model, think, c);
      const parsed = JSON.parse(a.raw);
      const got = sum(parsed.items ?? []);
      row = { ...row, ms: Math.round(a.ms), tokens: a.evalCount, items: parsed.items, got: Object.fromEntries(Object.entries(got).map(([k, v]) => [k, r1(v)])), ...score(c, got, (parsed.items ?? []).length), thinking: a.thinking };
    } catch (e) {
      row = { ...row, error: String(e.message ?? e), pass: false };
    }
    rows.push(row);
    const g = row.got;
    console.log(`${row.pass ? 'PASS' : 'FAIL'} ${c.id.padEnd(24)} ${g ? `${g.kcal} kcal P${g.p} C${g.c} F${g.f} fib${g.fiber}` : row.error}  (ref ${c.ref.kcal}/${c.ref.p}/${c.ref.c}/${c.ref.f}/${c.ref.fiber})  ${row.ms ?? '-'}ms`);
  }
  const out = resolve(here, 'results', `${spec.replace(/[:+/]/g, '_')}${only ? '_' + only : ''}.json`);
  writeFileSync(out, JSON.stringify({ model: spec, system: SYSTEM, rows }, null, 2));
  for (const kind of ['text', 'image']) {
    const k = rows.filter((r) => r.kind === kind);
    if (!k.length) continue;
    const errs = k.filter((r) => r.kcalErrPct !== undefined && !r.expectEmpty).map((r) => r.kcalErrPct).sort((a, b) => a - b);
    const med = errs.length ? errs[Math.floor(errs.length / 2)] : NaN;
    const avgMs = k.filter((r) => r.ms).reduce((a, r) => a + r.ms, 0) / Math.max(1, k.filter((r) => r.ms).length);
    console.log(`${kind}: ${k.filter((r) => r.pass).length}/${k.length} pass, median kcal error ${r1(med)}%, avg ${Math.round(avgMs)} ms`);
  }
}
