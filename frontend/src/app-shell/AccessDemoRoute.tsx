import AccessDemo from "@roles/admin/access/AccessDemo";

// Standalone preview of the admin access screens. Lives in the shell so that
// app/ routes import the shell and nothing deeper.
export default function AccessDemoRoute(): React.JSX.Element {
  return <AccessDemo />;
}
