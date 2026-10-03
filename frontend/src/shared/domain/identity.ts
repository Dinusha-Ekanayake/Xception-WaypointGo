/** Safe personal MCP connection context, mirrored from Identity's published contract. */
export type McpContextView = {
  userId: string;
  roles: string[];
  scope: string[];
  readActions: string[];
};

/** A registered remote MCP client's consent-screen identity. Names are self-declared. */
export interface McpAuthorizationView { clientName: string; redirectHost: string }

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
