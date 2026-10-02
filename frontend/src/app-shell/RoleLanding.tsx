import { ROLE_ADDRESSES, isPreviewHome, sharedHomeFor } from "./hostRole.ts";
import type { ShellRole } from "./session.ts";

// The address every role shares has no sign-in of its own, on production and on
// preview alike: it says what Waypoint is and hands each of the four field
// roles to its own address, where the session lives.

const ROLES: { role: ShellRole; title: string; does: string }[] = [
  { role: "store_manager", title: "Store manager", does: "Place the store's order and confirm what arrived." },
  { role: "dispatcher", title: "Dispatcher", does: "Turn confirmed orders into a plan for the day's trucks." },
  { role: "loader", title: "Loader", does: "Load each truck against the plan and record the checks." },
  { role: "driver", title: "Driver", does: "Deliver stop by stop and capture proof, online or offline." },
];

export default function RoleLanding({ host }: { host: string }): React.JSX.Element {
  const preview = isPreviewHome(host);
  return (
    <div className="flex min-h-dvh flex-col bg-go-canvas px-4 pt-6 pb-10 font-go text-go-ink md:px-16 md:pt-12">
      <div className="flex items-center gap-3">
        <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
        {preview && <span className="rounded-full bg-white px-3 py-1 text-[13px] font-medium text-go-muted">Preview</span>}
      </div>

      <div className="mx-auto flex w-full max-w-[880px] flex-1 flex-col gap-8 py-10 md:pt-[8vh]">
        <div className="flex flex-col gap-3">
          <h1 className="text-[40px] leading-tight font-medium text-black md:text-[48px]">Waypoint Dispatch</h1>
          <p className="text-[19px] font-medium text-black">One delivery. Every handoff accounted for.</p>
          <p className="max-w-[640px] text-[15px] text-black/80">
            Waypoint keeps one delivery record from the store order to the store signature. It connects confirmed demand, explainable allocation, loading checks, offline
            delivery proof and independent store receipt, for the four roles that hand a delivery to each other.
          </p>
        </div>

        <nav aria-label="Choose a role" className="flex flex-col gap-3">
          <h2 className="text-[15px] font-medium text-go-muted">Choose your role to sign in</h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {ROLES.map(({ role, title, does }) => (
              <a
                key={role}
                href={`https://${sharedHomeFor(host, role, ROLE_ADDRESSES)}/`}
                className="flex min-h-24 flex-col justify-center gap-1 rounded-[24px] bg-white p-6 shadow-[0_5px_20px_rgba(0,0,0,0.09)] outline-none focus-visible:ring-2 focus-visible:ring-go-teal"
              >
                <span className="text-[19px] font-medium text-black">{title}</span>
                <span className="text-[14px] text-go-muted">{does}</span>
              </a>
            ))}
          </div>
        </nav>

        {preview && <p className="text-[13px] text-go-muted">This is the preview environment: unreviewed changes and demo data, kept apart from production.</p>}
      </div>
    </div>
  );
}
