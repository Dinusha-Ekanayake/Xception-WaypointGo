import assert from "node:assert/strict";
import { test } from "node:test";
import { wordsAct } from "../src/shared/messaging/words.ts";

// R-ML-43: the rules' reading when Jev is not sure, mostly Singlish on the chat set.
test("short words in four ways of writing read as what they do", () => {
  const cases: Array<[string, string | null]> = [
    ["ba, ada ganna ba. heta enna", "DELIVER_NEXT_DAY"],
    ["No, deliver tomorrow", "DELIVER_NEXT_DAY"],
    ["நாளை கொண்டு வாருங்கள்", "DELIVER_NEXT_DAY"],
    ["oyata tikak inna puluwanda? lorry eka parakku wenawa", "ASKS_CAN_WAIT"],
    ["can u wait", "ASKS_CAN_WAIT"],
    ["විනාඩි තිහක් ඉන්න පුළුවන්ද?", "ASKS_CAN_WAIT"],
    ["lorry eka kiyatada enne?", "ASKS_WHEN"],
    ["dan kade arala, enna", "STORE_OPEN_NOW"],
    ["ok no problem", "SAYS_YES"],
    ["hari, kamak na", "SAYS_YES"],
    ["අද ගන්න බෑ", "SAYS_NO"],
    ["we cannt acept at hat time", "SAYS_NO"],
    ["thank you aiye", "THANKS"],
  ];
  for (const [text, act] of cases) assert.equal(wordsAct(text), act, text);
});

test("a question is never read as a yes or a no, and long or empty words are not read", () => {
  assert.equal(wordsAct("Is it ok if we come after your closing time today?"), null);
  assert.equal(wordsAct("No problem?"), null);
  assert.equal(wordsAct(""), null);
  assert.equal(wordsAct(`ok ${"x".repeat(130)}`), null);
  assert.equal(wordsAct("<script>alert(1)</script>"), null);
  assert.equal(wordsAct("about the broken shelf in aisle three"), null);
});
