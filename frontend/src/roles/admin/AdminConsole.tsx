"use client";

import AccessDemo from "./access/AccessDemo";

// The admin workspace: one sidebar over access and operations. Sign in and sign
// out belong to the shell, as for every other role.

export default function AdminConsole({ displayName }: { displayName?: string }): React.JSX.Element {
  return <AccessDemo displayName={displayName} />;
}
