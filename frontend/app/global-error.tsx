"use client";

import ErrorBoundary from "@app-shell/ErrorBoundary.tsx";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}): React.JSX.Element {
  return (
    <html lang="en">
      <body className="bg-go-canvas font-go text-go-ink antialiased">
        <ErrorBoundary error={error} reset={reset} />
      </body>
    </html>
  );
}
