import { test, expect, type Page, type Route, type TestInfo } from "@playwright/test";

const DEVICE = "mock/phone";
const MODEL = "Samsung Mock Device";
const PASSWORD = "mock-admin-password";

async function mockDashboard(page: Page, options: { saved?: boolean; theme?: "light" | "dark" } = {}) {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(({ saved, theme, password }) => {
    // Seed only the first document: reload must exercise the application's
    // storage, and logging out must not be undone by this mock fixture.
    if (!sessionStorage.getItem("dashboard-mock-seeded")) {
      if (saved) localStorage.setItem("wgc_admin_password", password);
      else localStorage.removeItem("wgc_admin_password");
      localStorage.setItem("wgc_admin_theme", theme);
      sessionStorage.setItem("dashboard-mock-seeded", "1");
    }
    class Events extends EventTarget {
      constructor() { super(); setTimeout(() => this.dispatchEvent(new Event("ready")), 0); }
      close() {}
    }
    Object.assign(window, { EventSource: Events });
  }, { saved: options.saved ?? true, theme: options.theme ?? "light", password: PASSWORD });
  const current = () => ({
    id: 11, short_id: "mock0001", active: true, state: "active",
    device_model: MODEL, android_version: "16", wechat_version: "8.0.79", module_version: "0.1.22-wechat8079/26",
    owner_wxid: "mock_owner", wechat_nickname: "测试账号", switch_pending: false,
    last_seen_at: "2026-10-18T04:00:00Z", last_active_at: "2026-10-18T04:00:00Z"
  });
  const fulfill = (route: Route, json: unknown) => route.fulfill({ json });
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/modules/status")) return fulfill(route, { modules: [{
      device: DEVICE, device_nickname: "模拟设备绑定", device_wxid: "mock_owner", enabled: true,
      account_generation: 6, runtime_status: "ready", pending_outbox: 1, failed_outbox: 0,
      last_register_at: "2026-10-18T04:00:00Z", last_poll_at: "2026-10-18T04:00:00Z",
      installations: [current(), {
        id: 22, short_id: "mock0002", active: false, state: "standby",
        device_model: MODEL, owner_wxid: "mock_owner", switch_pending: false
      }, {
        id: 33, short_id: "mock0003", active: false, state: "offline", device_model: MODEL,
        owner_wxid: "mock_owner", module_version: "0.1.18", switch_pending: false
      }]
    }] });
    if (url.pathname.endsWith("/api-keys")) return fulfill(route, { api_keys: [] });
    if (url.pathname.endsWith("/module-contacts")) return fulfill(route, { contacts: [{
      id: 1, device: DEVICE, owner_wxid: "mock_owner", wxid: "mock_friend", remark: "模拟联系人",
      nickname: "测试昵称", chatroom: false, deleted: false
    }] });
    if (url.pathname.endsWith("/messages")) return fulfill(route, { messages: [{
      id: 5, device: DEVICE, owner_wxid: "mock_owner", chat_id: "mock_friend", direction: "recv",
      from_wxid: "mock_friend", to_wxid: "mock_owner", text: "仅用于布局测试的模拟消息，未读取真实聊天。",
      message_type: 1, created_at: "2026-10-18T04:00:00Z"
    }] });
    if (url.pathname.endsWith("/outbox")) return fulfill(route, { server_time: "2026-10-18T04:00:30Z", items: [{
      id: 9, device: DEVICE, owner_wxid: "mock_owner", wxid: "mock_friend", status: "pending",
      text: "模拟待发送内容：" + "long-content-".repeat(14), attempt_count: 0,
      created_at: "2026-10-18T04:00:00Z", updated_at: "2026-10-18T04:00:00Z"
    }] });
    return route.fulfill({ status: 404, json: {} });
  });
  return { errors };
}

async function expectNoHorizontalOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() =>
    Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) - window.innerWidth
  )).toBeLessThanOrEqual(1);
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  await expectNoHorizontalOverflow(page);
  const path = testInfo.outputPath(name + ".png");
  await page.screenshot({ path, fullPage: true });
  await testInfo.attach(name, { path, contentType: "image/png" });
}

for (const theme of ["light", "dark"] as const) {
  test(`dashboard navigation has no horizontal overflow in ${theme} theme`, async ({ page }, testInfo) => {
    const { errors } = await mockDashboard(page, { theme });
    await page.emulateMedia({ colorScheme: theme });
    await page.goto("/");
    await expect(page.getByRole("button", { name: "刷新", exact: true })).toBeVisible();
    await expect(page.locator("#inline-send")).toBeEnabled();
    await expect(page.getByRole("region", { name: "发送记录" })).toBeVisible();
    await expect(page.getByTestId("installation-list").first()).not.toBeVisible();
    if (theme === "dark") await expect(page.locator("html")).toHaveClass(/\bdark\b/);
    else await expect(page.locator("html")).not.toHaveClass(/\bdark\b/);
    await capture(page, testInfo, "workbench-" + theme);

    await page.getByRole("button", { name: "设备与连接", exact: true }).click();
    const list = page.getByTestId("installation-list").first();
    await expect(list).toBeVisible();
    await expect(list.getByTestId("installation-row").filter({ hasText: "#mock0001" }).getByText(MODEL, { exact: true }).first()).toBeVisible();
    await expect(list.getByText("历史安装记录 · 1 条", { exact: true })).toBeVisible();
    await expect(page.locator("#inline-send")).not.toBeVisible();
    await capture(page, testInfo, "devices-" + theme);

    await page.getByRole("button", { name: "API 管理", exact: true }).click();
    await expect(page.getByRole("button", { name: "刷新 API Key", exact: true })).toBeVisible();
    await expect(list).not.toBeVisible();
    await capture(page, testInfo, "api-" + theme);

    await page.getByRole("button", { name: "工作台", exact: true }).click();
    await expect(page.locator("#inline-send")).toBeEnabled();
    await expect(page.getByRole("region", { name: "发送记录" })).toBeVisible();
    expect(errors).toEqual([]);
  });
}

test("login persists across reload and logout clears the saved password", async ({ page }) => {
  const { errors } = await mockDashboard(page, { saved: false });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "进入看台", exact: true })).toBeVisible();
  await page.getByLabel("管理密码", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "进入看台", exact: true }).click();
  await expect(page.locator("#inline-send")).toBeEnabled();
  await expect.poll(() => page.evaluate(() => localStorage.getItem("wgc_admin_password"))).toBe(PASSWORD);
  await page.reload();
  await expect(page.locator("#inline-send")).toBeEnabled();
  await expect(page.getByRole("button", { name: "进入看台", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(page.getByRole("button", { name: "进入看台", exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => localStorage.getItem("wgc_admin_password"))).toBeNull();
  await page.reload();
  await expect(page.getByRole("button", { name: "进入看台", exact: true })).toBeVisible();
  await expect(page.getByLabel("管理密码", { exact: true })).toHaveValue("");
  await expect(page.locator("#inline-send")).not.toBeVisible();
  expect(errors).toEqual([]);
});
