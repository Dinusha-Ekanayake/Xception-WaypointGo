// RFC 9457 problem details. The error body is part of the API contract because
// clients branch on it, so it is parsed into a type rather than read ad hoc.

export type Problem = {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance: string;
  /** Rule identifiers such as R-PLN-06, from docs/architecture/RULES-AND-POLICIES.md. */
  violations: string[];
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

  /** A stale version: the record moved under us and the write must be reviewed, never merged. */
  get isVersionConflict(): boolean {
    return this.problem.title === "VERSION_CONFLICT";
  }

  /** Worth queueing and retrying. A rejection on rules is not. */
  get isRetryable(): boolean {
    return this.problem.status >= 500 || this.problem.status === 503;
  }
}

export function parseProblem(status: number, body: unknown): Problem {
  const record = (body ?? {}) as Partial<Problem> & Record<string, unknown>;
  const { type: _t, title: _ti, status: _s, detail: _d, instance: _i, violations: _v, ...extensions } = record;
  return {
    type: record.type ?? "about:blank",
    title: record.title ?? "ERROR",
    status: record.status ?? status,
    detail: record.detail ?? "",
    instance: record.instance ?? "",
    violations: Array.isArray(record.violations) ? record.violations : [],
    extensions,
  };
}
