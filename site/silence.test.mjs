// node site/silence.test.mjs: the home page demo must cut the demo take where the app did.
// The app's saved cut for this take (settings below) kept these spans; its last span was then
// stretched by hand to the end of the file, so only the first eleven are compared.
import assert from "node:assert/strict";
import { keepSpans } from "./silence.js";
import { take } from "./take.js";

const levels = Float32Array.from(Buffer.from(take.levels, "base64"), (q) => -q / 2);
const app = [[0,1560],[1920,8360],[9100,11380],[12520,21680],[22180,37200],[38100,74740],[75540,85960],
  [86420,89020],[89640,92320],[92980,112080],[112480,128060]];
const spans = keepSpans(levels, take.frameMs, take.durationMs, { thresholdDb: -22.2, minSilenceMs: 350, padMs: 80 });
assert.equal(spans.length, 12);
assert.deepEqual(spans.slice(0, 11), app);
assert.deepEqual(keepSpans(levels, take.frameMs, take.durationMs, { thresholdDb: -130 }), [[0, take.durationMs]]);
console.log("silence.js matches the app");
