import { test, expect, type Page, type Route } from "@playwright/test";

const fixture = "/tests/fixtures/connection.html";

async function setup(page: Page, saved = "") {
  await page.clock.install();
  await page.addInitScript((password) => {
    if (password && !sessionStorage.getItem("initialized")) localStorage.setItem("wgc_admin_password", password);
    sessionStorage.setItem("initialized", "true");
    class Events extends EventTarget {
      static all: Events[] = [];
      closed = false;
      onerror: ((event: Event) => void) | null = null;
      constructor(readonly url: string) { super(); Events.all.push(this); }
      close() { this.closed = true; }
    }
    Object.assign(window, { EventSource: Events, connectionEvents: Events });
  }, saved);
}

async function event(page: Page, type: string, index = -1, data = '{"device":"phone"}') {
  await page.evaluate(({ type, index, data }) => {
    const all = (window as any).connectionEvents.all;
    const source = all.at(index);
    if (type === "error") source.onerror?.(new Event(type));
    else source.dispatchEvent(new MessageEvent(type, { data }));
  }, { type, index, data });
}

async function counts(page: Page) {
  return page.evaluate(() => {
    const all = (window as any).connectionEvents.all;
    return { total: all.length, open: all.filter((source: any) => !source.closed).length };
  });
}

test("typing does not connect; login persists and reload connects automatically with one stream", async ({ page }) => {
  let requests = 0;
  await setup(page);
  await page.route("**/api/modules/status", (route) => { requests++; return route.fulfill({ json: { modules: [{ device: "phone" }] } }); });
  await page.goto(fixture);
  await page.getByLabel("password", { exact: true }).pressSequentially("secret");
  await page.clock.runFor(1000);
  expect(requests).toBe(0);
  expect(await counts(page)).toEqual({ total: 0, open: 0 });
  await page.getByRole("button", { name: "Login", exact: true }).click();
  await expect(page.getByTestId("modules")).toHaveText("phone");
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
  await event(page, "ready");
  await expect(page.getByTestId("live")).toHaveText("true");
  await page.getByRole("button", { name: "Change selection" }).click();
  await page.getByLabel("password", { exact: true }).fill("draft edit");
  await event(page, "message");
  await expect(page.getByTestId("message")).toHaveText("second:phone");
  expect(requests).toBe(1);
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
  await page.reload();
  await expect(page.getByTestId("password")).toHaveText("secret");
  await expect(page.getByTestId("modules")).toHaveText("phone");
  expect(requests).toBe(2);
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
});

test("invalid saved credentials stop retries and require explicit login", async ({ page }) => {
  let requests = 0;
  await setup(page, "wrong");
  await page.route("**/api/modules/status", (route) => { requests++; return route.fulfill({ status: 401, json: { error: "unauthorized" } }); });
  await page.goto(fixture);
  await expect(page.getByTestId("error")).toContainText("重新登录");
  await page.clock.runFor(60000);
  await page.evaluate(() => { window.dispatchEvent(new Event("online")); window.dispatchEvent(new Event("pageshow")); });
  expect(requests).toBe(1);
  expect(await counts(page)).toEqual({ total: 0, open: 0 });
  expect(await page.evaluate(() => localStorage.getItem("wgc_admin_password"))).toBeNull();
});

test("SSE errors revalidate credentials; a 401 closes the session without an infinite EventSource retry", async ({ page }) => {
  let valid = true, requests = 0;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => {
    requests++;
    return route.fulfill({ status: valid ? 200 : 401, json: valid ? { modules: [{ device: "phone" }] } : {} });
  });
  await page.goto(fixture);
  await expect(page.getByTestId("modules")).toHaveText("phone");
  valid = false;
  await event(page, "error");
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
  await page.clock.runFor(1000);
  await expect(page.getByTestId("error")).toContainText("重新登录");
  await page.clock.runFor(60000);
  expect(requests).toBe(2);
  await expect(page.getByTestId("modules")).toBeEmpty();
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
});

test("offline recovery refreshes once and replaces the closed stream", async ({ page }) => {
  let requests = 0;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => { requests++; return route.fulfill({ json: { modules: [{ device: "phone" }] } }); });
  await page.route("**/api/api-keys**", (route) => route.fulfill({ json: { api_keys: [] } }));
  await page.goto(fixture);
  await expect(page.getByTestId("modules")).toHaveText("phone");
  await page.evaluate(() => { Object.defineProperty(navigator, "onLine", { value: false, configurable: true }); window.dispatchEvent(new Event("offline")); });
  await page.clock.runFor(100);
  expect(requests).toBe(1);
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
  await page.evaluate(() => {
    Object.defineProperty(navigator, "onLine", { value: true, configurable: true });
    window.dispatchEvent(new Event("online")); window.dispatchEvent(new Event("pageshow"));
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect(page.getByTestId("refreshes")).toHaveText("1");
  expect(requests).toBe(2);
  expect(await counts(page)).toEqual({ total: 2, open: 1 });
});

test("a hung verification times out and retries without creating a duplicate stream", async ({ page }) => {
  let requests = 0;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => {
    requests++;
    if (requests === 1) return;
    return route.fulfill({ json: { modules: [{ device: "recovered" }] } });
  });
  await page.goto(fixture);
  await expect.poll(() => requests).toBe(1);
  await page.clock.runFor(10000);
  await expect(page.getByTestId("error")).not.toBeEmpty();
  await page.clock.runFor(1000);
  await expect(page.getByTestId("modules")).toHaveText("recovered");
  expect(requests).toBe(2);
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
  await expect(page.getByTestId("error")).toBeEmpty();
});

test("manual refreshes merge and operation errors do not corrupt connection state", async ({ page }) => {
  let requests = 0, delayed: Route | undefined;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => {
    requests++;
    if (requests === 2) { delayed = route; return; }
    return route.fulfill({ json: { modules: [{ device: "phone" }] } });
  });
  await page.route("**/api/api-keys**", (route) => route.fulfill({ json: { api_keys: [] } }));
  await page.goto(fixture);
  await expect(page.getByTestId("modules")).toHaveText("phone");
  await event(page, "ready");
  await page.getByRole("button", { name: "Operation error" }).click();
  await expect(page.getByTestId("error")).toBeEmpty();
  await expect(page.getByTestId("live")).toHaveText("true");
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect.poll(() => !!delayed).toBe(true);
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  expect(requests).toBe(2);
  await delayed!.fulfill({ json: { modules: [{ device: "updated" }] } });
  await expect(page.getByTestId("refreshes")).toHaveText("1");
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
});

test("status polls share the request with full refreshes without losing the full refresh", async ({ page }) => {
  let requests = 0, delayed: Route | undefined;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => {
    requests++;
    if (requests === 3) { delayed = route; return; }
    return route.fulfill({ json: { modules: [{ device: `phone-${requests}` }] } });
  });
  await page.route("**/api/api-keys**", (route) => route.fulfill({ json: { api_keys: [] } }));
  await page.goto(fixture);
  await expect(page.getByTestId("modules")).toHaveText("phone-1");
  await page.getByRole("button", { name: "Refresh status", exact: true }).click();
  await expect(page.getByTestId("modules")).toHaveText("phone-2");
  await expect(page.getByTestId("refreshes")).toHaveText("0");
  await page.getByRole("button", { name: "Refresh status", exact: true }).click();
  await expect.poll(() => !!delayed).toBe(true);
  await expect(page.getByTestId("connecting")).toHaveText("false");
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByTestId("connecting")).toHaveText("true");
  await page.getByRole("button", { name: "Refresh status", exact: true }).click();
  expect(requests).toBe(3);
  await delayed!.fulfill({ json: { modules: [{ device: "phone-3" }] } });
  await expect(page.getByTestId("refreshes")).toHaveText("1");
  await expect(page.getByTestId("modules")).toHaveText("phone-3");
  await expect(page.getByTestId("connecting")).toHaveText("false");
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
});

test("authorization failure in a dependent refresh terminates the same credential session", async ({ page }) => {
  let requests = 0;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => { requests++; return route.fulfill({ json: { modules: [{ device: "phone" }] } }); });
  await page.route("**/api/api-keys**", (route) => route.fulfill({ status: 401, json: {} }));
  await page.goto(fixture);
  await expect(page.getByTestId("modules")).toHaveText("phone");
  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect(page.getByTestId("error")).toContainText("重新登录");
  await page.clock.runFor(60000);
  expect(requests).toBe(2);
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
});

test("old authentication and stream callbacks cannot republish after changing credentials or logout", async ({ page }) => {
  let delayed: Route | undefined;
  await setup(page);
  await page.route("**/api/modules/status", (route) => {
    const password = route.request().headers()["x-bridge-password"];
    if (password === "old") { delayed = route; return; }
    return route.fulfill({ json: { modules: [{ device: password }] } });
  });
  await page.goto(fixture);
  await page.getByLabel("password", { exact: true }).fill("old");
  await page.getByRole("button", { name: "Login", exact: true }).click();
  await expect.poll(() => !!delayed).toBe(true);
  await page.getByLabel("password", { exact: true }).fill("new");
  await page.getByRole("button", { name: "Login", exact: true }).click();
  await expect(page.getByTestId("modules")).toHaveText("new");
  await delayed!.fulfill({ json: { modules: [{ device: "STALE" }] } });
  await page.getByRole("button", { name: "Logout", exact: true }).click();
  await event(page, "ready");
  await event(page, "message");
  await event(page, "error");
  await page.clock.runFor(60000);
  await expect(page.getByTestId("modules")).toBeEmpty();
  await expect(page.getByTestId("message")).toBeEmpty();
  await expect(page.getByTestId("live")).toHaveText("false");
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
});

test("StrictMode and unmount cleanup leave no second stream or pending retry", async ({ page }) => {
  let requests = 0;
  await setup(page, "secret");
  await page.route("**/api/modules/status", (route) => { requests++; return route.fulfill({ json: { modules: [{ device: "phone" }] } }); });
  await page.goto(`${fixture}?strict`);
  await expect(page.getByTestId("modules")).toHaveText("phone");
  expect(await counts(page)).toEqual({ total: 1, open: 1 });
  await event(page, "error");
  await page.getByRole("button", { name: "Toggle mount" }).click();
  const before = requests;
  await page.clock.runFor(60000);
  expect(requests).toBe(before);
  expect(await counts(page)).toEqual({ total: 1, open: 0 });
});
