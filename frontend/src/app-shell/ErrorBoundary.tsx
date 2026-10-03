"use client";

import { useEffect } from "react";
import { StructuredError } from "@shared/ui";

export default function ErrorBoundary({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset?: () => void;
}): React.JSX.Element {
  useEffect(() => {
    console.error("Waypoint application error:", error);
  }, [error]);

  return (
    <StructuredError
      code={500}
      title="Something went wrong"
      message="An unexpected error occurred while loading this view.&#10;Don't worry: your work is saved safely on this phone."
      actionLabel="Try again"
      onAction={
        reset ??
        (() => {
          if (typeof window !== "undefined") {
            window.location.reload();
          }
        })
      }
    />
  );
}
