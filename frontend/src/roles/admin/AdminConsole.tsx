"use client";

import AccessDemo from "./access/AccessDemo";

// The admin workspace: one sidebar over the access, operations and live AI
// assistants (#177, at #assistants) screens. Sign in and sign out belong to the
// shell, as for every other role.

export default function AdminConsole({ displayName }: { displayName?: string }): React.JSX.Element {
  return <AccessDemo displayName={displayName} />;
}
