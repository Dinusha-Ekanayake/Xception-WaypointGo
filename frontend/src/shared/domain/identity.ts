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

/** Command kinds an administrator sends about assistant apps and the people using them. */
export const McpCommandKind = {
  BlockClient: "mcp:BlockClient",
  UnblockClient: "mcp:UnblockClient",
  /** Ends every MCP connection of one person; payload `{userId, reason}`, no version (R-IAM-38). */
  RevokeUserConnections: "mcp:RevokeUserConnections",
} as const;

/** The policies that switch MCP per person or role (R-IAM-36, R-IAM-37). */
export const McpSwitchPolicy = {
  Blocked: "WaypointMcpBlocked",
  NoWrites: "WaypointMcpNoWrites",
  PersonalReader: "WaypointMcpPersonalReader",
} as const;
export type McpSwitchPolicyName = (typeof McpSwitchPolicy)[keyof typeof McpSwitchPolicy];

/** GET /api/mcp/access/roles and /api/mcp/access/people: one principal's MCP switches (R-IAM-38). */
export type McpPrincipalAccessView = {
  principalType: "user" | "role";
  /** A user id or a role code. */
  principalId: string;
  /** A person's display name or a role's description. */
  label: string;
  /** The switch policies attached directly to this principal. */
  policies: McpSwitchPolicyName[];
  /** Open MCP connections; always 0 for a role. */
  liveConnections: number;
};

/** GET /api/policies: one policy, with the version an attach or detach must name. */
export type PolicySummaryView = {
  policyId: string;
  name: string;
  versionNumber: number;
  rowVersion: number;
};

/** Policy commands; payload `{name, principalType, principalId}`, guarded by the policy's rowVersion. */
export const PolicyCommandKind = { Attach: "iam:AttachPolicy", Detach: "iam:DetachPolicy" } as const;

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

/**
 * Safe account profile and authorization scope returned by Identity's
 * account directory reads (GET /api/accounts and /api/accounts/{id}),
 * mirrored from AccountQuery.AccountView.
 */
export type AccountView = {
  userId: string;
  email: string;
  displayName: string;
  active: boolean;
  rowVersion: number;
  roles: string[];
  depots: string[];
  outlets: string[];
};

/**
 * Vehicle driver assignment view, mirrored from AccountQuery.AssignmentView.
 */
export type AssignmentView = {
  assignmentId: string;
  vehicleId: string;
  driverUserId: string;
  driverName: string;
  from: string | null;
  until: string | null;
  rowVersion: number;
};

/**
 * Role catalogue item from Identity (GET /api/admin/roles),
 * mirrored from AdminAccessQuery.Role.
 */
export type RoleView = {
  roleCode: string;
  description: string;
  memberCount?: number;
  totalPolicyCount?: number;
  activePolicyCount?: number;
};

/**
 * Action catalogue item from Identity (GET /api/admin/actions),
 * mirrored from AdminAccessQuery.Action.
 */
export type ActionView = {
  action: string;
  module: string;
  description: string;
  implemented: boolean;
};

export type PolicyStatementView = {
  policyId: string;
  name: string;
  version: number;
  effect: string;
  actions: string[];
  resources: string[];
};

export type AccountAccessView = {
  userId: string;
  roles: string[];
  scope: string[];
  policies?: PolicyStatementView[];
  inheritedPolicies?: PolicyStatementView[];
  directPolicies?: PolicyStatementView[];
};
