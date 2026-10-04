import { request } from "@shared/api/client";
import { newCommand, send } from "@shared/api/commands";

export type PlanningParameter = {
  key: string;
  category: string;
  label: string;
  unit: string;
  control: "number" | "time" | "locked";
  editable: boolean;
  minimum: number;
  maximum: number;
  value: number;
};

export type PlanningRuleSet = {
  ruleSetId: string;
  rowVersion: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  note: string;
  parameters: PlanningParameter[];
};

export type PlanningCatalogue = { active: PlanningRuleSet | null; scheduled: PlanningRuleSet | null };

export function fetchPlanningRules(signal?: AbortSignal) {
  return request<PlanningCatalogue>("/api/admin/constraints/planning", { signal });
}

export function createPlanningRuleSet(input: {
  key: string;
  value: string;
  effectiveFrom: string;
  reason: string;
  expectedVersion: number;
}) {
  return send<{ ruleSetId: string; rowVersion: number; effectiveFrom: string }>(
    newCommand("planning:CreateRuleSet", {
      key: input.key,
      value: input.value,
      effectiveFrom: input.effectiveFrom,
      reason: input.reason,
    }, input.expectedVersion),
  );
}

export type CalendarDay = { date: string; operating: boolean; known: boolean;
  day: { holiday?: boolean; generated?: boolean } };

export function fetchCalendarDay(date: string) {
  return request<CalendarDay>(`/api/reference/calendar/${encodeURIComponent(date)}`);
}

export function setCalendarOperatingDay(date: string, operating: boolean, reason: string) {
  return send<{ date: string; operating: boolean; reason: string }>(
    newCommand("calendar:Override", { date, operating, reason }),
  );
}
