"use client";

import ErrorBoundary from "@app-shell/ErrorBoundary.tsx";

export default function ErrorPage({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}): React.JSX.Element {
  return <ErrorBoundary error={error} reset={reset} />;
}
