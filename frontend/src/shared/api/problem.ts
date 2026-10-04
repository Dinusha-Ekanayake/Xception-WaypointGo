// RFC 9457 problem details. The error body is part of the API contract because
// clients branch on it, so it is parsed into a type rather than read ad hoc.

/** One failed constraint. `rule` names an entry in docs/architecture/RULES-AND-POLICIES.md. */
export type Violation = {
  rule: string;
  field?: string;
  message: string;
};

export type Problem = {
  type: string;
  /** For people; may be reworded. Branch on `code`. */
  title: string;
  status: number;
  detail: string;
  instance: string;
  /** Stable machine-readable code, such as VERSION_CONFLICT. */
  code: string;
  /** Quote this when reporting a failure; it finds the request in the logs. */
  correlationId: string;
  violations: Violation[];
  /** Any other members of the problem body, such as per-line stock availability. */
  extensions: Record<string, unknown>;
};

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail || problem.title);
    this.name = "ApiError";
    this.problem = problem;
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string {
    return this.problem.code;
  }

  /** A stale version: the record moved under us and the write must be reviewed, never merged. */
  get isVersionConflict(): boolean {
    return this.problem.code === "VERSION_CONFLICT";
  }

  /** Worth queueing and retrying. A rejection on rules is not. */
  get isRetryable(): boolean {
    const { status } = this.problem;
    return status >= 500 || status === 408 || status === 429;
  }
}

/**
 * What a person reads for a failed read or command, in place of `error.message`.
 * A network drop and an HTML gateway page both arrive as a bare `Error`, never
 * as a `Problem`, so the detail a server did send (RFC 9457, AGENTS.md) is the
 * only message trusted as user-facing; anything else is reworded here.
 */
export function friendlyError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status >= 500) return "The server had a problem. Try again.";
    if (error.problem.detail) return error.problem.detail;
    return "Something went wrong. Try again.";
  }
  if (error instanceof TypeError || (error instanceof Error && /network|fetch/i.test(error.message))) {
    return "No connection. Try again.";
  }
  return "Something went wrong. Try again.";
}

function violationOf(value: unknown): Violation | null {
  // Before structured violations the backend sent bare rule ids. Accept both.
  if (typeof value === "string") return { rule: value, message: "" };
  if (value && typeof value === "object" && typeof (value as Violation).rule === "string") {
    const v = value as Violation;
    return { rule: v.rule, ...(typeof v.field === "string" ? { field: v.field } : {}), message: typeof v.message === "string" ? v.message : "" };
  }
  return null;
}

export function parseProblem(status: number, body: unknown): Problem {
  const record = (body && typeof body === "object" ? body : {}) as Partial<Record<keyof Problem, unknown>> & Record<string, unknown>;
  const { type: _t, title: _ti, status: _s, detail: _d, instance: _i, code: _c, correlationId: _ci, violations: _v, ...extensions } = record;
  const text = (value: unknown, fallback: string) => (typeof value === "string" ? value : fallback);
  // A body with no code predates the field: its title was the code.
  const code = text(record.code, text(record.title, "ERROR"));
  return {
    type: text(record.type, "about:blank"),
    title: text(record.title, "Error"),
    status: typeof record.status === "number" ? record.status : status,
    detail: text(record.detail, ""),
    instance: text(record.instance, ""),
    code,
    correlationId: text(record.correlationId, ""),
    violations: Array.isArray(record.violations)
      ? record.violations.map(violationOf).filter((v): v is Violation => v !== null)
      : [],
    extensions,
  };
}
