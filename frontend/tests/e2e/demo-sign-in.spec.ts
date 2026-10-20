import { expect, test, type Page } from "@playwright/test";

// Demo sign-in: in a configured demo the sign-in page offers its accounts to
// pick, and a pick fills the form; outside one there is nothing to pick. The
// password field can be shown and hidden again.

const UNAUTHENTICATED = JSON.stringify({ type: "about:blank", title: "Unauthenticated", status: 401, code: "UNAUTHENTICATED", detail: "Sign in", violations: [] });

async function serve(page: Page, demo: unknown) {
  const signIns: unknown[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    if (pathname === "/api/session/demo") return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(demo) });
    if (pathname === "/api/session" && request.method() === "POST") {
      signIns.push(request.postDataJSON());
      return route.fulfill({ status: 401, contentType: "application/problem+json", body: UNAUTHENTICATED });
    }
    return route.fulfill({ status: 401, contentType: "application/problem+json", body: UNAUTHENTICATED });
  });
  return signIns;
}

test("a demo account picked from the list fills the sign-in", async ({ page }) => {
  const signIns = await serve(page, {
    accounts: [
      { email: "dispatcher-demo@waypoint.local", name: "Demo Dispatcher", role: "dispatcher" },
      { email: "dock-demo@waypoint.local", name: "Demo Dock", role: "loader" },
    ],
    password: "demo-secret",
    pin: "2468",
  });
  await page.goto("/");

  await page.getByRole("button", { name: "Use a demo account" }).click();
  const list = page.getByRole("dialog", { name: "Demo accounts" });
  await expect(list).toContainText("Demo Dispatcher · desktop");
  await list.getByRole("button", { name: /Loader/ }).click();
  await expect(list).toBeHidden();
  await expect(page.getByLabel("Email")).toHaveValue("dock-demo@waypoint.local");
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("demo-secret");
  await expect(page.getByLabel("Password", { exact: true })).toHaveAttribute("type", "password");

  await page.getByRole("button", { name: "Sign in" }).click();
  await expect.poll(() => signIns.length).toBe(1);
  expect(signIns[0]).toMatchObject({ email: "dock-demo@waypoint.local", password: "demo-secret" });
});

test("the administrator is listed without its password and points to the shared credentials", async ({ page }) => {
  await serve(page, {
    accounts: [
      { email: "dispatcher-demo@waypoint.local", name: "Demo Dispatcher", role: "dispatcher", fillsPassword: true },
      { email: "admin@waypoint.local", name: "Administrator", role: "admin", fillsPassword: false },
    ],
    password: "demo-secret",
    pin: null,
  });
  await page.goto("/");

  await page.getByRole("button", { name: "Use a demo account" }).click();
  const list = page.getByRole("dialog", { name: "Demo accounts" });
  await expect(list).toContainText("password in the shared credentials on GitHub");
  await list.getByRole("button", { name: /Administrator/ }).first().click();
  await expect(page.getByLabel("Email")).toHaveValue("admin@waypoint.local");
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  await expect(page.getByRole("status")).toContainText("shared credentials on GitHub");
});

test("outside a demo there is nothing to pick, and the password can be shown and hidden", async ({ page }) => {
  await serve(page, { accounts: [], password: null, pin: null });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Welcome back" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Use a demo account" })).toHaveCount(0);

  const field = page.getByLabel("Password", { exact: true });
  await field.fill("typed-secret");
  await page.getByRole("button", { name: "Show password" }).click();
  await expect(field).toHaveAttribute("type", "text");
  await page.getByRole("button", { name: "Hide password" }).click();
  await expect(field).toHaveAttribute("type", "password");
});
