// The rules' own reading of a short chat message (R-ML-43): what it does, from
// a few plain words in English, Sinhala, Tamil and the two written in English
// letters. Used only when Jev has not read the message surely, which on the
// labelled chat set is mostly Singlish. First match wins, so the order matters:
// "No, deliver tomorrow" is the next day, "ok no problem" is a yes. A long
// message is rarely one act, so it gets no reading and the general replies stay;
// a question is only ever read as one ("is it ok if we come later?" is no yes).

const LONGEST = 120;

type Act = "DELIVER_NEXT_DAY" | "ASKS_CAN_WAIT" | "ASKS_WHEN" | "STORE_OPEN_NOW" | "SAYS_YES" | "SAYS_NO" | "THANKS";

// Latin words match whole; Sinhala and Tamil script match anywhere in the text.
const READS: Array<[Act, RegExp]> = [
  ["DELIVER_NEXT_DAY", /\b(tomorrow|next day|next delivery|heta|naalai|nalai)\b|හෙට|நாளை/],
  ["ASKS_CAN_WAIT", /\b(can (you|u) wait|inna puluwan(da|de)?|innako|balan inna|kaathiruka|kathiruka)\b|\bwait\b.*\?|ඉන්න පුළුවන්ද|රැඳී සිටිය හැකිද|காத்திருக்க முடியுமா/],
  ["ASKS_WHEN", /\b(when|what time|kiyatada|kawadada|eppo|eppothu)\b|කීයටද|කවදාද|எப்போது/],
  ["STORE_OPEN_NOW", /\b(open now|we are open|arala|arla|thirandhu)\b|ඇරලා|විවෘතයි|திறந்து/],
  ["SAYS_YES", /\b(no problem|kamak na(e|ha)?|yes|ok|okay|fine|sure|hari|ow|ehema|sari)\b|කමක් නෑ|හරි|ඔව්|சரி|ஆம்|பரவாயில்லை/],
  ["SAYS_NO", /\b(no|can'?t|cannt|cannot|not possible|ba|bae|bari|epa|illa|mudiyathu|mudiyaadhu)\b|බෑ|බැහැ|එපා|முடியாது|இல்லை/],
  ["THANKS", /\b(thanks?|thank you|thx|sthuthi|isthuthi|nandri)\b|ස්තූතියි|நன்றி/],
];

const QUESTIONS = new Set<Act>(["DELIVER_NEXT_DAY", "ASKS_CAN_WAIT", "ASKS_WHEN"]);

/** What a short message does, by its words, or null when none of them says. */
export function wordsAct(body: string): Act | null {
  const text = body.trim().toLowerCase();
  if (!text || text.length > LONGEST) return null;
  const asks = text.includes("?");
  return READS.find(([act, re]) => (!asks || QUESTIONS.has(act)) && re.test(text))?.[0] ?? null;
}
