import { test, expect, type Page, type Route } from "@playwright/test";
import type { FileTaskView } from "../src/types";

const sends = new WeakMap<Page, number>();
const timestamp = "2026-10-19T01:00:00Z";
function task(changes: Partial<FileTaskView> = {}): FileTaskView {
  return {
    id: 81, device: "phone", owner_wxid: "A", wxid: "friend", status: "unknown", attempt_count: 1,
    chat_record_id: 42, last_error: "Native result not confirmed", created_at: timestamp, updated_at: timestamp,
    native_started_at: timestamp, attachment: { id: "mock-file", name: "notice.zip", size: 2048, sha256: "mock-hash", expires_at: timestamp },
    server_copy: { state: "retained", updated_at: timestamp }, phone_copy_state: "not_reported",
    can_cleanup_server: true, can_cancel_unstarted: false, cleanup_blocked_reason: "", ...changes
  };
}

async function setup(page: Page, handle: (route: Route, url: URL) => Promise<boolean>, scope = { owner: "A", generation: 1 }) {
  sends.set(page, 0);
  await page.addInitScript(() => {
    localStorage.setItem("wgc_admin_password", "mock-password");
    class Events extends EventTarget {
      constructor() { super(); setTimeout(() => this.dispatchEvent(new Event("ready")), 0); }
      close() {}
    }
    Object.assign(window, { EventSource: Events });
  });
  await page.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    if (url.pathname.includes("/send/")) {
      sends.set(page, (sends.get(page) || 0) + 1);
      await route.fulfill({ status: 500, json: {} }); return;
    }
    if (await handle(route, url)) return;
    if (url.pathname.endsWith("/modules/status")) return route.fulfill({ json: { modules: [
      { device: "phone", device_wxid: scope.owner, account_generation: scope.generation, runtime_status: "ready" }
    ] } });
    if (url.pathname.endsWith("/api-keys")) return route.fulfill({ json: { api_keys: [] } });
    if (url.pathname.endsWith("/module-contacts")) return route.fulfill({ json: { contacts: [
      { id: 1, device: "phone", owner_wxid: scope.owner, wxid: "friend", remark: "测试好友", deleted: false }
    ] } });
    if (url.pathname.endsWith("/messages")) return route.fulfill({ json: { messages: [] } });
    if (url.pathname.endsWith("/outbox")) return route.fulfill({ json: { items: [], server_time: timestamp } });
    if (url.pathname === "/api/files") return route.fulfill({ json: { items: [], next_before_id: null, server_time: timestamp } });
    return route.fulfill({ status: 404, json: { error: "not_found" } });
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "文件暂存", exact: true })).toBeEnabled();
  await page.getByRole("button", { name: "文件暂存", exact: true }).click();
}

test.afterEach(async ({ page }) => { expect(sends.get(page) || 0).toBe(0); });

test("unknown cleanup is server-only, confirms scope, keeps unknown visible and submits once", async ({ page }, testInfo) => {
  const posts: Route[] = [];
  const item = task();
  const removed = task({ server_copy: { state: "removed", updated_at: timestamp }, can_cleanup_server: false, cleanup_blocked_reason: "already_removed" });
  let details = 0;
  await setup(page, async (route, url) => {
    if (url.pathname === "/api/files") {
      expect(url.searchParams.get("status")).toBe("retained");
      expect(url.searchParams.get("owner_wxid")).toBe("A");
      await route.fulfill({ json: { items: [item], next_before_id: null, server_time: timestamp } }); return true;
    }
    if (url.pathname === "/api/files/81/cleanup") { posts.push(route); return true; }
    if (url.pathname === "/api/files/81") { details++; await route.fulfill({ json: { item: removed, server_time: timestamp } }); return true; }
    return false;
  });
  const panel = page.getByRole("dialog", { name: "文件暂存", exact: true });
  const row = panel.getByTestId("file-task-row");
  await row.getByText("任务详情", { exact: true }).click();
  await expect(row.getByText("手机副本：未上报", { exact: true })).toBeVisible();
  await expect(row.getByText("手机报告结果未确认", { exact: true })).toBeVisible();
  await expect(row.getByText(/发放一次发送许可：/)).toBeVisible();
  await panel.screenshot({ path: testInfo.outputPath("file-maintenance-unknown.png") });
  let confirmation = "";
  page.once("dialog", async dialog => { confirmation = dialog.message(); await dialog.dismiss(); });
  await row.getByRole("button", { name: "清理后端副本", exact: true }).click();
  expect(confirmation).toContain("不会撤回微信消息、清理手机副本、重新发送或改变发送结果");
  expect(confirmation).toContain("此任务结果仍未确认");
  expect(posts).toHaveLength(0);
  page.once("dialog", dialog => dialog.accept());
  await row.getByRole("button", { name: "清理后端副本", exact: true }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
  await expect.poll(() => posts.length).toBe(1);
  expect(posts[0].request().postDataJSON()).toEqual({ device: "phone", owner_wxid: "A", mode: "server_only" });
  await posts[0].fulfill({ json: { item: removed, server_time: timestamp } });
  await expect(row.getByText("后端副本：已清理", { exact: true })).toBeVisible();
  await expect(row.getByText("手机报告结果未确认", { exact: true })).toBeVisible();
  await expect(row.getByText("手机副本：未上报", { exact: true })).toBeVisible();
  await row.getByRole("button", { name: "刷新任务状态", exact: true }).click();
  await expect.poll(() => details).toBe(1);
  await expect(row.getByText("已重新读取任务状态，未触发发送。", { exact: true })).toBeVisible();
  expect(posts).toHaveLength(1);
});

test("active lease blocks cleanup while unstarted cancellation requires its explicit mode", async ({ page }) => {
  const posts: unknown[] = [];
  const leased = task({ id: 82, status: "leased", native_started_at: undefined, lease_until: timestamp,
    can_cleanup_server: false, can_cancel_unstarted: false, cleanup_blocked_reason: "active_lease" });
  const pending = task({ id: 83, status: "pending", native_started_at: undefined, can_cleanup_server: false,
    can_cancel_unstarted: true, cleanup_blocked_reason: "unstarted_task" });
  await setup(page, async (route, url) => {
    if (url.pathname === "/api/files") { await route.fulfill({ json: { items: [leased, pending], next_before_id: null, server_time: timestamp } }); return true; }
    if (url.pathname === "/api/files/83/cleanup") {
      posts.push(route.request().postDataJSON());
      await route.fulfill({ json: { item: { ...pending, status: "cancelled", server_copy: { state: "removed" }, can_cancel_unstarted: false }, server_time: timestamp } }); return true;
    }
    return false;
  });
  const leasedRow = page.getByTestId("file-task-row").filter({ hasText: "任务 #82" });
  await expect(leasedRow.getByText("已领取 · 尚未获微信发送许可", { exact: true })).toBeVisible();
  await expect(leasedRow.getByRole("button", { name: "清理后端副本", exact: true })).toBeDisabled();
  await expect(leasedRow.getByText(/暂不能清理/)).toBeVisible();
  const pendingRow = page.getByTestId("file-task-row").filter({ hasText: "任务 #83" });
  let confirmation = "";
  page.once("dialog", async dialog => { confirmation = dialog.message(); await dialog.accept(); });
  await pendingRow.getByRole("button", { name: "取消未提交任务并清理后端", exact: true }).click();
  await expect(pendingRow.getByText("已取消", { exact: true })).toBeVisible();
  expect(confirmation).toContain("取消后任务将不再发送");
  expect(posts).toEqual([{ device: "phone", owner_wxid: "A", mode: "cancel_unstarted" }]);
});

test("202 remains pending and a reported cleanup failure can be retried without resending", async ({ page }) => {
  const modes: string[] = [];
  const pending = task({ server_copy: { state: "cleanup_pending", cleanup_requested_at: timestamp }, can_cleanup_server: true });
  await setup(page, async (route, url) => {
    if (url.pathname === "/api/files") { await route.fulfill({ json: { items: [task({ status: "native_started" })], next_before_id: null, server_time: timestamp } }); return true; }
    if (url.pathname === "/api/files/81/cleanup") {
      modes.push(route.request().postDataJSON().mode);
      const first = modes.length === 1;
      await route.fulfill({ status: first ? 202 : 200, json: { item: first ? pending : task({ server_copy: { state: "removed" }, can_cleanup_server: false }), server_time: timestamp } }); return true;
    }
    if (url.pathname === "/api/files/81") {
      await route.fulfill({ json: { item: { ...pending, server_copy: { ...pending.server_copy, last_error: "remove_failed" } }, server_time: timestamp } }); return true;
    }
    return false;
  });
  const row = page.getByTestId("file-task-row");
  await expect(row.getByText("已发放一次许可 · 等待结果", { exact: true })).toBeVisible();
  page.once("dialog", dialog => dialog.accept());
  await row.getByRole("button", { name: "清理后端副本", exact: true }).click();
  await expect(row.getByText("后端副本：待清理", { exact: true })).toBeVisible();
  await expect(row.getByText("后端副本：已清理", { exact: true })).toHaveCount(0);
  await row.getByRole("button", { name: "刷新任务状态", exact: true }).click();
  await expect(row.getByRole("alert")).toHaveText(/后端清理失败：后端暂时无法删除文件/);
  page.once("dialog", dialog => dialog.accept());
  await row.getByRole("button", { name: "重试清理", exact: true }).click();
  await expect(row.getByText("后端副本：已清理", { exact: true })).toBeVisible();
  expect(modes).toEqual(["server_only", "server_only"]);
});

test("409 rechecks the existing task and never switches cleanup mode automatically", async ({ page }) => {
  let posts = 0, reads = 0;
  await setup(page, async (route, url) => {
    if (url.pathname === "/api/files") { await route.fulfill({ json: { items: [task({ status: "pending", native_started_at: undefined, can_cleanup_server: false, can_cancel_unstarted: true })], next_before_id: null, server_time: timestamp } }); return true; }
    if (url.pathname === "/api/files/81/cleanup") {
      posts++; expect(route.request().postDataJSON().mode).toBe("cancel_unstarted");
      await route.fulfill({ status: 409, json: { error: "file_cleanup_conflict", message: "task changed" } }); return true;
    }
    if (url.pathname === "/api/files/81") { reads++; await route.fulfill({ json: { item: task({ status: "native_started" }), server_time: timestamp } }); return true; }
    return false;
  });
  const row = page.getByTestId("file-task-row");
  page.once("dialog", dialog => dialog.accept());
  await row.getByRole("button", { name: "取消未提交任务并清理后端", exact: true }).click();
  await expect(row.getByText("已发放一次许可 · 等待结果", { exact: true })).toBeVisible();
  await expect(row.getByText("任务状态已变化，本次未清理；请查看最新状态。", { exact: true })).toBeVisible();
  await expect(row.getByRole("button", { name: "清理后端副本", exact: true })).toBeEnabled();
  expect(posts).toBe(1); expect(reads).toBe(1);
});

test("pagination finds old unknown tasks independently of the recent outbox and filters retain them after cleanup", async ({ page }) => {
  const queries: URL[] = [];
  await setup(page, async (route, url) => {
    if (url.pathname !== "/api/files") return false;
    queries.push(url);
    const first = !url.searchParams.has("before_id") && url.searchParams.get("status") === "retained";
    await route.fulfill({ json: { items: [first ? task({ id: 101, status: "sent" }) : task({ id: 1, created_at: "2025-01-01T00:00:00Z", server_copy: { state: "removed" }, can_cleanup_server: false })],
      next_before_id: first ? 101 : null, server_time: timestamp } }); return true;
  });
  await page.getByRole("button", { name: "加载更早任务", exact: true }).click();
  await expect(page.getByTestId("file-task-row")).toHaveCount(2);
  expect(queries.some(url => url.searchParams.get("before_id") === "101")).toBe(true);
  await page.getByRole("button", { name: "未确认", exact: true }).click();
  await expect(page.getByTestId("file-task-row")).toHaveCount(1);
  await expect(page.getByTestId("file-task-row").getByText(/任务 #1 ·/)).toBeVisible();
  await expect(page.getByText("手机报告结果未确认", { exact: true })).toBeVisible();
  expect(queries.at(-1)?.searchParams.get("status")).toBe("unconfirmed");
  await page.getByRole("button", { name: "全部文件", exact: true }).click();
  await expect.poll(() => queries.at(-1)?.searchParams.get("status")).toBe("all");
  expect(queries.every(url => url.searchParams.get("device") === "phone" && url.searchParams.get("owner_wxid") === "A" && url.searchParams.get("limit") === "50")).toBe(true);
});

test("account changes abort a pending cleanup and late responses cannot appear in the new scope", async ({ page }) => {
  const scope = { owner: "A", generation: 1 };
  let held: Route | undefined;
  const queries: URL[] = [];
  await setup(page, async (route, url) => {
    if (url.pathname === "/api/files") {
      queries.push(url);
      await route.fulfill({ json: { items: [task({ id: scope.owner === "A" ? 81 : 91, owner_wxid: scope.owner })], next_before_id: null, server_time: timestamp } }); return true;
    }
    if (url.pathname === "/api/files/81/cleanup") { held = route; return true; }
    return false;
  }, scope);
  page.once("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: "清理后端副本", exact: true }).click();
  await expect.poll(() => !!held).toBe(true);
  scope.owner = "B"; scope.generation = 2;
  // Trigger the same status refresh used by the background connection while the modal is open.
  await page.getByRole("button", { name: "刷新", exact: true }).evaluate((button: HTMLButtonElement) => button.click());
  await expect(page.getByRole("dialog", { name: "文件暂存", exact: true })).toHaveCount(0);
  await held!.fulfill({ json: { item: task({ server_copy: { state: "removed" }, can_cleanup_server: false }), server_time: timestamp } });
  await page.getByRole("button", { name: "文件暂存", exact: true }).click();
  await expect(page.getByTestId("file-task-row").getByText(/任务 #91 ·/)).toBeVisible();
  await expect(page.getByTestId("file-task-row").getByText(/任务 #81 ·/)).toHaveCount(0);
  await expect(page.getByText(/后端副本已清理；手机副本未上报/)).toHaveCount(0);
  expect(queries.at(-1)?.searchParams.get("owner_wxid")).toBe("B");
});
