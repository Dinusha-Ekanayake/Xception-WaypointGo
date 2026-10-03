/** Safe personal MCP connection context, mirrored from Identity's published contract. */
export type McpContextView = {
  userId: string;
  roles: string[];
  scope: string[];
  readActions: string[];
  /** Issue #177: what this connection was granted (R-IAM-34). */
  grantedScopes: string[];
  /** The confirmed write tools it may use now (R-IAM-35). */
  writeTools: string[];
  /** Whether personal fields may be returned (R-IAM-36). */
  personalFields: boolean;
};

/** A registered remote MCP client's consent-screen identity. Names are self-declared. */
export interface McpAuthorizationView { clientName: string; redirectHost: string; scopes: string[] }

/** GET /api/mcp/connections: one of your own connected assistants (issue #177). */
export type McpConnectionView = {
  connectionId: string;
  clientId: string | null;
  /** Self-declared by the assistant at registration; null for a local connection. */
  clientName: string | null;
  transport: "local" | "remote";
  scopes: string[];
  connectedAt: string;
  lastUsedAt: string;
};

/** GET /api/mcp/clients: a registered assistant app, for an administrator (R-IAM-37). */
export type McpClientView = {
  clientId: string;
  clientName: string;
  registeredAt: string;
  lastUsedAt: string | null;
  activeConnections: number;
  blockedAt: string | null;
  blockReason: string | null;
  rowVersion: number;
};

/** GET /api/audit/mcp-usage: calls per tool and assistant app over a window. */
export type McpUsageView = {
  tool: string;
  /** Null for local connections, which have no app. */
  clientId: string | null;
  calls: number;
  ok: number;
  denied: number;
  rateLimited: number;
  notFound: number;
  errors: number;
  p95Ms: number | null;
  /** Ten or more refusals in the window: misconfigured or probing. */
  attention: boolean;
};

/** Command kinds an administrator sends about assistant apps. */
export const McpCommandKind = { BlockClient: "mcp:BlockClient", UnblockClient: "mcp:UnblockClient" } as const;

/** GET /api/profile: a person's own account (R-IAM-32), with the version a change sends back. */
export type ProfileView = {
  userId: string;
  email: string;
  displayName: string;
  phone: string | null;
  rowVersion: number;
};

export const IdentityCommandKind = {
  updateOwnProfile: "iam:UpdateOwnProfile",
} as const;

/**
 * iam:UpdateOwnProfile. Always the actor's own account. The profile as it should
 * be, so a blank phone clears it. The email (the sign-in name) and the password
 * are not part of it.
 */
export type UpdateOwnProfile = {
  displayName: string;
  phone: string | null;
};
