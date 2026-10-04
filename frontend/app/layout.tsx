import "@fontsource/instrument-sans/400.css";
import "@fontsource/instrument-sans/500.css";
import "@fontsource/instrument-sans/600.css";
import "@fontsource/instrument-sans/700.css";
import "@fontsource/ibm-plex-mono/400.css";
import "@fontsource/ibm-plex-mono/500.css";
import "@fontsource-variable/google-sans-flex/wght.css";
import type { Metadata, Viewport } from "next";
import localFont from "next/font/local";
import type { ReactNode } from "react";
import "./globals.css";

const unMalithi = localFont({
  src: "../public/fonts/UN-Malithi-4.ttf",
  variable: "--font-sinhala",
  display: "swap",
});

// The manifest link comes from app/manifest.ts and the home screen icon from
// app/apple-touch-icon.png, both chosen by the role address (issue #201), so
// this layout stays static and the offline shell stays one page.
export const metadata: Metadata = {
  title: "Waypoint Dispatch",
  description: "One delivery record, from the store order to the store signature.",
  applicationName: "Waypoint",
  icons: { icon: "/icons/app/waypoint-192.png", apple: "/apple-touch-icon.png" },
  // No apple title: iOS then names the home screen icon from the manifest, which follows the address.
  appleWebApp: { capable: true, statusBarStyle: "default" },
  formatDetection: { telephone: false },
};

// `viewportFit: cover` lets an installed app draw under the notch and the home
// bar; bars at the bottom pad with env(safe-area-inset-bottom) (see theme.css).
export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#0e766d" },
    { media: "(prefers-color-scheme: dark)", color: "#0b1413" },
  ],
};

export default function RootLayout({
  children,
}: {
  children: ReactNode;
}): React.JSX.Element {
  return (
    <html lang="en">
      <body className={unMalithi.variable}>{children}</body>
    </html>
  );
}
