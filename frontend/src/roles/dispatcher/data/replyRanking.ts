import type { ReplyRankingView } from "@shared/domain/types";

// Roadmap AI-J5: what the dispatcher is told about the ready replies' order.
// The rules decide which replies are offered; the ranker only orders them, and
// the screen says which is in charge (rule 9: degrade visibly).

export function rankingNote(ranking: ReplyRankingView | null, failed: boolean): string {
  if (ranking?.trained) return `Order learned from ${ranking.posts} replies`;
  if (failed || ranking?.unavailable) return "Suggestions: rules only (learning unavailable)";
  return "Suggestions: rules only";
}
