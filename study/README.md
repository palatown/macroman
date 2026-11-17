# Local model study

A quick check of how well open-weight models running on a desktop handle macroman's food prompt, done to see
whether a local model could stand in for Claude. Short answer: not yet, at least not for me.

`run.mjs` takes the food system prompt straight out of `MacroAi.kt`, sends each case to Ollama with the same JSON
schema the app uses (temperature 0), and checks the totals against expected values.

- `cases.json`: 16 text descriptions and 7 nutrition-label images, with expected totals and tolerances.
- `labels/`: the label images are made-up labels rendered from HTML with headless Edge (`node study/labels/make.mjs`).
- `results/`: raw output from each run.

```sh
ollama pull gemma4:26b-a4b
node study/run.mjs gemma4:26b-a4b gemma4:12b gemma4:12b+think
```

`+think` turns on the model's thinking mode. `ONLY=text` or `ONLY=image` runs half the cases, and `OLLAMA_URL`
points it at another machine.

## Results

Run on an RTX 4070 Ti SUPER (16 GB VRAM) with 64 GB of RAM, so models bigger than the card spill onto the CPU.
Passes out of 16 text cases and 7 labels, latest prompt first:

| Model | Ollama tag | Text | Labels | Time per request |
|---|---|---|---|---|
| **Gemma 4 26B-A4B** | `gemma4:26b-a4b` | **14** | **6** | ~1.5 s |
| Qwen 3.6 35B-A3B | `qwen3.6:35b-a3b` | 13 | 5 | ~2 s |
| Ministral 3 14B | `ministral-3:14b` | 14 | 3 | ~2.5 s |
| Qwen 3.5 9B | `qwen3.5:9b` | 10 | 4 | ~1.3 s |
| Qwen3-VL 8B | `qwen3-vl:8b` | – | – | 25–70 s, stopped |
| Qwen 3.6 35B-A3B, thinking | | | 3 | ~28 s |

Earlier runs, before the prompt gained the alcohol and calories-per-gram rules:

| Model | Text | Labels | Time per request |
|---|---|---|---|
| Gemma 3 12B | 10 | 4 | |
| Gemma 4 12B | 13 | 4 | ~0.6 s |
| Gemma 4 12B, thinking | 13 | 5 | 4–27 s |
| Gemma 4 26B-A4B | 14 | 5 | 1–3 s |
| Gemma 4 26B-A4B, thinking | 11 | 3 | |

Thinking is off unless it says otherwise. If you're running a model locally for macroman, `gemma4:26b-a4b` is the
one to use: the most accurate here and also the fastest of the bigger ones. It's 18 GB, so on a 16 GB card a bit
of it runs on the CPU, but only about 4B parameters are active per token, so that barely matters.

The big caveat: the image cases are made-up nutrition labels, not photos of food on a plate. Judging portions
from a real photo is the hard part and isn't measured here.

Things I noticed:

- Every model missed the same two: a Chipotle bowl (guessed ~850 kcal, the real number is ~675) and an EU label
  that lists fiber separately from carbs.
- Most models fail the label where you ate 1.5 servings: they report one serving.
- Thinking mode doesn't pay off. It's 10–20x slower, and the models sometimes looped on the prompt's "keep
  calories consistent with 4/4/9" rule until they ran out of context and returned nothing.
- Qwen3-VL 8B got stuck producing output for a minute or more per request even with thinking off, so it was
  stopped. The harness now caps output at 1500 tokens so a stuck model fails quickly.

Claude wasn't scored here; the point was to see how close a local model gets.
