"use client";

import { StructuredError } from "@shared/ui";

export default function NotFound(): React.JSX.Element {
  return (
    <StructuredError
      code={404}
      title="Page not found"
      message="The page or destination you requested could not be found.&#10;Don't worry: your work is saved safely on this phone."
      actionLabel="Return to home"
      onAction={() => {
        if (typeof window !== "undefined") {
          window.location.href = "/";
        }
      }}
    />
  );
}
