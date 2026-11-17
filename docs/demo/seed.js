// Fills the emulator's database with a believable week of meals for the README demo.
const { DatabaseSync } = require("node:sqlite");
const db = new DatabaseSync(process.argv[2]);
const photo = process.argv[3]; // file name of the seeded label photo

const at = (daysAgo, hh, mm) => {
  const d = new Date(2026, 10, 16 - daysAgo, hh, mm);
  const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
  return { day, time: d.getTime() };
};

const F = {
  latte: ["Iced Latte", "12 fl oz, 2% milk", 130, 8, 12, 5, 0],
  yogurt: ["Greek Yogurt Bowl", "170 g yogurt, 1/2 cup berries, 2 tbsp granola", 290, 22, 34, 7, 4],
  oats: ["Overnight Oats", "1 jar, ~300 g", 380, 18, 52, 11, 8],
  eggs: ["Scrambled Eggs", "3 large eggs with 1 tsp butter", 255, 19, 2, 19, 0],
  toast: ["Sourdough Toast", "1 slice, ~50 g", 140, 5, 27, 1, 1],
  club: ["Turkey Club", "1 sandwich on wheat", 560, 36, 44, 26, 5],
  salad: ["Chicken Caesar Salad", "~3 cups with dressing", 520, 38, 18, 33, 4],
  pho: ["Beef Pho", "1 large bowl", 640, 38, 82, 14, 3],
  burrito: ["Chicken Burrito Bowl", "rice, beans, chicken, salsa, cheese", 720, 46, 78, 22, 14],
  salmon: ["Salmon", "6 oz fillet, baked", 350, 39, 0, 21, 0],
  rice: ["White Rice", "1 cup cooked, ~160 g", 205, 4, 45, 0, 1],
  broccoli: ["Roasted Broccoli", "1.5 cups with olive oil", 120, 5, 11, 7, 4],
  pasta: ["Spaghetti Bolognese", "~2 cups", 690, 34, 82, 22, 6],
  apple: ["Apple", "1 medium, ~180 g", 95, 0, 25, 0, 4],
  shake: ["Protein Shake", "1 scoop whey in water", 120, 24, 3, 1, 0],
  almonds: ["Almonds", "1 oz, ~23 nuts", 165, 6, 6, 14, 4],
  bar: ["Protein Bar", "1 bar (60 g)", 230, 20, 24, 8, 10],
};

const rows = [];
const add = (k, daysAgo, hh, mm, extra = {}) => {
  const [name, quantity, calories, protein, carbs, fat, fiber] = F[k];
  rows.push({ ...at(daysAgo, hh, mm), name, quantity, calories, protein, carbs, fat, fiber, photo: null, ...extra });
};

const week = [
  [6, ["latte", 7, 50], ["eggs", 8, 5], ["toast", 8, 5], ["club", 12, 15], ["apple", 15, 30], ["salmon", 19, 0], ["rice", 19, 0], ["broccoli", 19, 0]],
  [5, ["latte", 7, 40], ["yogurt", 7, 45], ["salad", 12, 35], ["shake", 17, 30], ["pasta", 19, 20]],
  [4, ["latte", 8, 0], ["oats", 8, 10], ["club", 12, 5], ["almonds", 15, 0], ["salmon", 18, 50], ["rice", 18, 50]],
  [3, ["latte", 7, 45], ["yogurt", 7, 50], ["pho", 12, 40], ["apple", 16, 0], ["pasta", 19, 30]],
  [2, ["latte", 7, 55], ["eggs", 8, 10], ["toast", 8, 10], ["burrito", 12, 20], ["shake", 17, 45], ["salmon", 19, 10], ["broccoli", 19, 10]],
  [1, ["latte", 7, 50], ["yogurt", 7, 55], ["salad", 12, 25], ["almonds", 15, 20], ["pho", 19, 0]],
  [0, ["latte", 7, 45], ["yogurt", 7, 50]],
];
for (const [daysAgo, ...meals] of week) for (const [k, hh, mm] of meals) add(k, daysAgo, hh, mm);
add("bar", 0, 10, 30, { photo });

db.exec("DELETE FROM food; DELETE FROM workout; DELETE FROM water;");
const ins = db.prepare(
  "INSERT INTO food (day, time, name, quantity, calories, protein, carbs, fat, fiber, photo) VALUES (?,?,?,?,?,?,?,?,?,?)",
);
for (const r of rows) ins.run(r.day, r.time, r.name, r.quantity, r.calories, r.protein, r.carbs, r.fat, r.fiber, r.photo);
const water = db.prepare("INSERT INTO water (day, ml) VALUES (?, ?)");
for (let i = 0; i <= 6; i++) water.run(at(i, 12, 0).day, i === 0 ? 1000 : 2000);
db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
db.close();
console.log(`seeded ${rows.length} food rows`);
