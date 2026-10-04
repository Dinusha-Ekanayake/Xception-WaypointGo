"use client";

import type { ReactNode } from "react";
import { ApiError } from "@shared/api/problem";
import { Notice } from "@shared/ui";
import { ruleLabel } from "@shared/wording";

/**
 * Why a read or a command failed, in the server's own words, with every rule it
 * named. A refusal on a rule lists each violation; an outage says so and offers
 * the retry, which is safe because commands are idempotent.
 */
/** The words of a refusal or an outage: a title, the server's reasons one per line, and the rules it named. */
export function refusalText(error: Error, what: string): { title: string; lines: string[]; rules: string[] } {
  const api = error instanceof ApiError ? error : null;
  const status = api?.status ?? 0;
  const title =
    status === 401
      ? "Your session has ended. Sign in again."
      : status === 403
        ? `You do not have access to ${what}`
        : status === 409 && api?.isVersionConflict
          ? "Someone else changed this first"
          : status === 409 || status === 422 || status === 400
            ? `${what[0]!.toUpperCase()}${what.slice(1)} was refused`
            : `${what[0]!.toUpperCase()}${what.slice(1)} could not be reached`;
  const violations = api?.problem.violations ?? [];
  // The server joins several reasons with "; "; each is shown on its own line.
  const lines = [
    ...error.message.split("; ").filter(Boolean),
    ...violations.filter((violation) => violation.message).map((violation) => `${violation.rule}: ${violation.message}`),
  ];
  const rules = [...new Set(violations.filter((violation) => !violation.message).map((violation) => violation.rule))];
  return { title, lines, rules };
}

export default function Refusal({ error, what, action }: { error: Error; what: string; action?: ReactNode }): React.JSX.Element {
  const api = error instanceof ApiError ? error : null;
  const status = api?.status ?? 0;
  const title =
    status === 401
      ? "Your session has ended. Sign in again."
      : status === 403
        ? `You do not have access to ${what}`
        : status === 409 && api?.isVersionConflict
          ? "Someone else changed this first"
          : status === 409 || status === 422 || status === 400
            ? `${what[0]!.toUpperCase()}${what.slice(1)} was refused`
            : `${what[0]!.toUpperCase()}${what.slice(1)} could not be reached`;
  const violations = api?.problem.violations ?? [];
  const explained = violations.filter((violation) => violation.message);
  // The server joins several reasons with "; "; each is shown on its own line.
  const lines = error.message.split("; ").filter(Boolean);
  const rules = [...new Set(violations.filter((violation) => !violation.message).map((violation) => violation.rule))];
  return (
    <Notice tone="danger" title={title} live action={action}>
      {lines.length > 1 ? (
        <span className="flex flex-col gap-1">
          {lines.map((line, index) => (
            <span key={index}>{line}</span>
          ))}
        </span>
      ) : (
        error.message
      )}
      {explained.length > 0 && (
        <span className="mt-1.5 flex flex-col gap-1">
          {explained.map((violation, index) => (
            <span key={`${violation.rule}-${index}`} className="flex gap-2">
              <span className="shrink-0 text-[11px] font-medium">{ruleLabel(violation.rule)}</span>
              <span>{violation.message}</span>
            </span>
          ))}
        </span>
      )}
      {rules.length > 0 && (
        <span className="mt-1.5 flex flex-wrap gap-1.5">
          {rules.map((rule) => (
            <span key={rule} className="rounded-go-chip bg-white px-1.5 py-0.5 text-[11px] font-medium">
              {ruleLabel(rule)}
            </span>
          ))}
        </span>
      )}
      {rules.length > 0 && (
        <details className="mt-1.5 text-[11px]">
          <summary className="cursor-pointer text-go-secondary">Details for support</summary>
          <span className="mt-1 flex flex-wrap gap-1.5">
            {rules.map((rule) => (
              <code key={rule} className="rounded-go-chip bg-white px-1.5 py-0.5 font-medium">
                {rule}
              </code>
            ))}
          </span>
        </details>
      )}
    </Notice>
  );
}
