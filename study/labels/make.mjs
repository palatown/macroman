// Renders the synthetic nutrition labels to PNG with headless Edge.
import { writeFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const dir = resolve('study/labels'), out = resolve('study/images');
mkdirSync(out, { recursive: true });

const us = (o) => `<div class="nf"><h1>Nutrition Facts</h1><div class="thin"></div>
<div class="row">${o.servings}</div><div class="row"><b>Serving size</b><b>${o.size}</b></div><div class="thick"></div>
<div class="row" style="font-size:12px"><b>Amount per serving</b></div><div class="cal"><span>Calories</span><span>${o.kcal}</span></div><div class="med"></div>
<div class="row"><span><b>Total Fat</b> ${o.fat}g</span></div><div class="thin"></div>
<div class="row ind">Saturated Fat ${o.sat}g</div><div class="thin"></div>
<div class="row"><span><b>Sodium</b> ${o.na}mg</span></div><div class="thin"></div>
<div class="row"><span><b>Total Carbohydrate</b> ${o.carb}g</span></div><div class="thin"></div>
<div class="row ind">Dietary Fiber ${o.fiber}g</div><div class="thin"></div>
<div class="row ind">Total Sugars ${o.sugar}g</div><div class="thin"></div>
<div class="row"><span><b>Protein</b> ${o.protein}g</span></div><div class="thick"></div>
<div class="row" style="font-size:11px">* The % Daily Value tells you how much a nutrient in a serving of food contributes to a daily diet.</div></div>`;

const dual = `<div class="nf" style="width:420px"><h1>Nutrition Facts</h1><div class="thin"></div>
<div class="row">3 servings per container</div><div class="row"><b>Serving size</b><b>1/3 pizza (128g)</b></div><div class="thick"></div>
<div class="row"><span></span><b>Per serving</b><b>Per container</b></div>
<div class="cal"><span>Calories</span><span>310</span><span>930</span></div><div class="med"></div>
<div class="row"><b>Total Fat</b><span>13g</span><span>39g</span></div><div class="thin"></div>
<div class="row ind"><span>Saturated Fat</span><span>6g</span><span>18g</span></div><div class="thin"></div>
<div class="row"><b>Sodium</b><span>640mg</span><span>1920mg</span></div><div class="thin"></div>
<div class="row"><b>Total Carbohydrate</b><span>34g</span><span>102g</span></div><div class="thin"></div>
<div class="row ind"><span>Dietary Fiber</span><span>2g</span><span>6g</span></div><div class="thin"></div>
<div class="row ind"><span>Total Sugars</span><span>4g</span><span>12g</span></div><div class="thin"></div>
<div class="row"><b>Protein</b><span>14g</span><span>42g</span></div><div class="thick"></div></div>`;

const eu = (title, rows) => `<table class="eu"><tr><th colspan="2">${title}</th></tr>${rows.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('')}</table>`;

const labels = {
  bar: us({ servings: '12 servings per container', size: '1 bar (60g)', kcal: 230, fat: 8, sat: 3, na: 210, carb: 24, fiber: 10, sugar: 1, protein: 20 }),
  cereal: us({ servings: 'About 11 servings per container', size: '2/3 cup (40g)', kcal: 150, fat: 2, sat: 0, na: 190, carb: 33, fiber: 4, sugar: 9, protein: 3 }),
  pb: us({ servings: 'About 15 servings per container', size: '2 tbsp (32g)', kcal: 190, fat: 16, sat: 3, na: 140, carb: 7, fiber: 3, sugar: 2, protein: 7 }),
  pizza: dual,
  yogurt_eu: eu('Nutrition / Nährwerte per 100 g', [['Energy', '397 kJ / 95 kcal'], ['Fat', '3.0 g'], ['of which saturates', '2.0 g'], ['Carbohydrate', '4.0 g'], ['of which sugars', '4.0 g'], ['Protein', '10 g'], ['Salt', '0.10 g']]),
  muesli_eu: eu('Typical values per 100 g', [['Energy', '1561 kJ / 372 kcal'], ['Fat', '7.1 g'], ['of which saturates', '1.2 g'], ['Carbohydrate', '58 g'], ['of which sugars', '16 g'], ['Fibre', '8.5 g'], ['Protein', '10 g'], ['Salt', '0.05 g']]),
  chips_photo: `<div class="photo">${us({ servings: 'About 8 servings per container', size: '1 oz (28g/About 15 chips)', kcal: 160, fat: 10, sat: 1.5, na: 170, carb: 15, fiber: 1, sugar: 0, protein: 2 })}</div>`,
};

for (const [name, body] of Object.entries(labels)) {
  const html = resolve(dir, `${name}.html`);
  writeFileSync(html, `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="style.css"></head><body><div style="display:inline-block;padding:20px">${body}</div></body></html>`);
  const png = resolve(out, `${name}.png`);
  execFileSync(edge, ['--headless=new', '--disable-gpu', '--hide-scrollbars', `--screenshot=${png}`, '--window-size=560,760', pathToFileURL(html).href], { stdio: 'ignore' });
  console.log('wrote', png);
}
