import { request } from "@shared/api/client";
import type { Operator } from "./session.ts";

export type CrewMember = Pick<Operator, "userId" | "displayName" | "employeeCode">;

export function crew(): Promise<CrewMember[]> {
  return request<CrewMember[]>("/api/session/crew");
}

export function switchOperator(userId: string, pin: string): Promise<Operator> {
  return request<Operator>("/api/session/operator", {
    method: "POST",
    body: { userId, pin },
  });
}

export async function lockOperator(): Promise<void> {
  await request<null>("/api/session/operator", { method: "DELETE" });
}
