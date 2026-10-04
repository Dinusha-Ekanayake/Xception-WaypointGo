import { notFound } from "next/navigation";
import GalleryRoute from "@app-shell/GalleryRoute";

// The shared components on one page, for building screens (issue #28).
// Development only: a production build answers 404 here.
export default function GalleryPage(): React.JSX.Element {
  if (process.env.NODE_ENV === "production") notFound();
  return <GalleryRoute />;
}
