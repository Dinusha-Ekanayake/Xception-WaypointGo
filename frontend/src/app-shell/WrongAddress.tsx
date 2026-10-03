import { hostForRole, sharedHostFor } from "./hostRole.ts";
import { ROLE_DOES } from "./roleCards.ts";
import { ROLE_LABEL, type ShellRole } from "./session.ts";

// Signed in on another role's address. Rather than a dead end, offer the three
// ways on: the account's own roles, another account here, or every role.

export default function WrongAddress({
  host,
  pinned,
  displayName,
  roles,
  onSwitchAccount,
}: {
  host: string;
  pinned: ShellRole;
  displayName: string;
  roles: ShellRole[];
  onSwitchAccount: () => void;
}): React.JSX.Element {
  const home = sharedHostFor(host);
  return (
    <div className="flex min-h-dvh flex-col bg-go-canvas px-4 pt-6 pb-10 font-go text-go-ink md:px-16 md:pt-12">
      <div className="flex items-center justify-between gap-3">
        <span className="text-[40px] leading-none font-extrabold text-black">GO</span>
        {home && (
          <a href={`https://${home}/`} className="flex min-h-10 items-center gap-1.5 rounded-full bg-white px-4 text-[14px] font-medium text-[#031b08] outline-none focus-visible:ring-2 focus-visible:ring-go-teal">
            <span aria-hidden="true">←</span> All roles
          </a>
        )}
      </div>

      <section aria-label="Wrong address" className="mx-auto flex w-full max-w-[720px] flex-1 flex-col gap-8 py-10 md:pt-[8vh]">
        <div className="flex flex-col gap-3">
          <h1 className="text-[36px] leading-tight font-medium text-black md:text-[44px]">This is the {ROLE_LABEL[pinned].toLowerCase()} address</h1>
          <p className="text-[15px] text-black/80">
            You are signed in as <span className="font-medium text-black">{displayName}</span>, who does not hold the {ROLE_LABEL[pinned].toLowerCase()} role.
          </p>
        </div>

        {roles.length > 0 && (
          <nav aria-label="Your roles" className="flex flex-col gap-3">
            <h2 className="text-[15px] font-medium text-go-muted">Continue where your account belongs</h2>
            <div className="grid gap-3 sm:grid-cols-2">
              {roles.map((r) => (
                <a
                  key={r}
                  href={`https://${hostForRole(host, r)}/`}
                  className="flex min-h-24 flex-col justify-center gap-1 rounded-[24px] bg-white p-6 shadow-[0_5px_20px_rgba(0,0,0,0.09)] outline-none focus-visible:ring-2 focus-visible:ring-go-teal"
                >
                  <span className="flex items-center justify-between text-[19px] font-medium text-black">
                    {ROLE_LABEL[r]} <span aria-hidden="true" className="text-go-teal">→</span>
                  </span>
                  {ROLE_DOES[r] && <span className="text-[14px] text-go-muted">{ROLE_DOES[r]}</span>}
                </a>
              ))}
            </div>
          </nav>
        )}

        <div className="flex flex-col gap-3 rounded-[24px] bg-white p-6 sm:flex-row sm:items-center sm:justify-between">
          <span className="text-[15px] text-black/80">Have a {ROLE_LABEL[pinned].toLowerCase()} account?</span>
          <button type="button" onClick={onSwitchAccount} className="min-h-12 rounded-[16px] bg-[#031a0c] px-5 text-[15px] font-medium text-white">
            Sign in with another account
          </button>
        </div>
      </section>
    </div>
  );
}
