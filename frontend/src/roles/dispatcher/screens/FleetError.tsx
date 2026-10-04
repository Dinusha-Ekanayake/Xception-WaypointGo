"use client";

import { ApiError, friendlyError } from "@shared/api/problem";
import { Notice } from "@shared/ui";

/** Why the fleet read failed, in words a dispatcher can act on, with a retry. */
export default function FleetError({ error, onRetry }: { error: Error; onRetry: () => void }): React.JSX.Element {
  const status = error instanceof ApiError ? error.status : 0;
  const title =
    status === 403
      ? "You do not have access to this depot's fleet"
      : status === 401
        ? "Your session has ended. Sign in again."
        : status >= 500 || status === 0
          ? "The fleet could not be loaded"
          : "The fleet request was refused";
  return (
    <Notice
      tone="danger"
      title={title}
      live
      action={
        status === 403 ? undefined : (
          <button type="button" onClick={onRetry} className="relative shrink-0 rounded-go-chip bg-white px-2.5 py-[5px] text-[11px] font-medium text-go-teal before:absolute before:-inset-x-1 before:-inset-y-2.5">
            Try again
          </button>
        )
      }
    >
      {friendlyError(error)}
    </Notice>
  );
}
