import { test, expect, type Page, type Route } from "@playwright/test";

async function setup(page: Page, handle?: (route: Route, url: URL) => Promise<boolean>, offline = false) {
  await page.addInitScript(() => {
    localStorage.setItem("wgc_admin_password", "test");
    class Events extends EventTarget {
      constructor() { super(); setTimeout(() => this.dispatchEvent(new Event("ready")), 0); }
      close() {}
    }
    Object.assign(window, { EventSource: Events });
  });
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    if (await handle?.(route, url)) return;
    if (url.pathname.endsWith("/modules/status")) return route.fulfill({ json: { modules: [
      { device: "phone", device_wxid: "A", account_generation: 1, runtime_status: "ready" }
    ] } });
    if (url.pathname.endsWith("/api-keys")) return route.fulfill({ json: { api_keys: [] } });
    if (url.pathname.endsWith("/module-contacts")) return route.fulfill({ json: { contacts: [
      { id: 1, device: "phone", owner_wxid: "A", wxid: "friend", remark: "好友甲", chatroom: false, deleted: false },
      { id: 2, device: "phone", owner_wxid: "A", wxid: "other", remark: "好友乙", chatroom: false, deleted: false }
    ] } });
    if (url.pathname.endsWith("/messages")) return route.fulfill({ json: { messages: [] } });
    if (url.pathname.endsWith("/outbox")) return route.fulfill({ json: { items: [], server_time: "2026-10-17T00:02:05Z" } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "连接刷新", exact: true }).click();
  if (!offline) await expect(page.locator("#inline-send")).toBeEnabled();
}

test("queue shows content, honest stages, server wait age and confirmation deadline", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const queries: URL[] = [];
  const base = { device: "phone", owner_wxid: "A", wxid: "friend", attempt_count: 1,
    created_at: "2026-10-17T00:00:00Z", updated_at: "2026-10-17T00:00:30Z" };
  await setup(page, async (route, url) => {
    if (!url.pathname.endsWith("/outbox")) return false;
    queries.push(url);
    const active = url.searchParams.get("status") === "active";
    await route.fulfill({ json: { server_time: "2026-10-17T00:02:05Z", items: active ? [
      { ...base, id: 1, status: "pending", text: "尚未领取的正文", attempt_count: 0 },
      { ...base, id: 2, status: "leased", text: "等微信回执的正文", lease_until: "2026-10-17T00:01:30Z" },
      { ...base, id: 88, owner_wxid: "B", status: "pending", text: "不能出现的其他账号正文" }
    ] : [
      { ...base, id: 3, status: "failed", text: "结果不确定的正文", last_error: "WeChat message queued but delivery unconfirmed; local msgId=42" },
      { ...base, id: 4, status: "sent", text: "已有回执的正文" },
      { ...base, id: 5, status: "cancelled", text: "已取消的正文" }
    ] } });
    return true;
  });
  const queue = page.getByRole("region", { name: "发送记录" });
  await expect(queue.getByText("尚未领取的正文", { exact: true })).toBeVisible();
  await expect(queue.getByText("已分派 · 待回执", { exact: true })).toBeVisible();
  await expect(queue.getByText("已等待 2 分 5 秒", { exact: true }).first()).toBeVisible();
  await expect(queue.getByText(/已超时待恢复/)).toBeVisible();
  await expect(queue.getByText("微信已确认发送", { exact: true })).toHaveCount(0);
  await expect(page.getByText("不能出现的其他账号正文")).toHaveCount(0);
  await queue.getByRole("button", { name: "最近记录", exact: true }).click();
  await expect(queue.getByText("结果未确认", { exact: true })).toBeVisible();
  await expect(queue.getByText("微信已接收任务，但尚未确认结果；请先查看聊天记录，避免重复发送。", { exact: true })).toBeVisible();
  await expect(queue.getByText("微信已确认发送", { exact: true })).toBeVisible();
  await expect(queue.getByText("已取消", { exact: true })).toBeVisible();
  await queue.getByLabel("仅当前会话").check();
  await expect.poll(() => queries.at(-1)?.searchParams.get("wxid")).toBe("friend");
  expect(queries.every(url => url.searchParams.get("device") === "phone" && url.searchParams.get("owner_wxid") === "A")).toBe(true);
  expect(errors).toEqual([]);
});

test("same-turn double submit sends once; late success cannot clear another chat draft", async ({ page }) => {
  let held: Route | undefined;
  const sends: unknown[] = [];
  await setup(page, async (route, url) => {
    if (!url.pathname.endsWith("/send/text")) return false;
    sends.push(route.request().postDataJSON()); held = route; return true;
  });
  await page.locator("#inline-send").fill("只发送一次");
  const submit = page.getByRole("button", { name: "加入队列", exact: true });
  await submit.evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
  await expect.poll(() => sends.length).toBe(1);
  await expect(page.locator("#inline-send")).toBeDisabled();
  await page.getByRole("button").filter({ hasText: "好友乙" }).click();
  await page.locator("#inline-send").fill("另一会话的新草稿");
  await held!.fulfill({ json: { ok: true, outbox_id: 42 } });
  await expect(page.locator("#inline-send")).toHaveValue("另一会话的新草稿");
  await expect(page.getByText(/任务 #42 已创建/)).toHaveCount(0);
  expect(sends[0]).toMatchObject({ owner_wxid: "A", account_generation: 1, wx_ids: ["friend"], text: "只发送一次" });
});

test("unknown HTTP outcome retains draft and never automatically resends", async ({ page }) => {
  let sends = 0;
  await setup(page, async (route, url) => {
    if (!url.pathname.endsWith("/send/text")) return false;
    sends++; await route.abort("failed"); return true;
  });
  await page.getByRole("button", { name: "发消息", exact: true }).click();
  await page.locator("#send-text").fill("需要确认的内容");
  await page.getByRole("button", { name: "加入队列", exact: true }).last().click();
  await expect(page.locator("#send-text")).toHaveValue("需要确认的内容");
  await expect(page.getByText(/未确认任务是否创建，请先查看发送记录/)).toBeVisible();
  await expect(page.getByRole("button", { name: "加入队列", exact: true }).last()).toBeEnabled();
  expect(sends).toBe(1);
});

test("accepted task remains traceable after immediate completion", async ({ page }) => {
  let submitted = false;
  await setup(page, async (route, url) => {
    if (url.pathname.endsWith("/send/text")) {
      submitted = true;
      await route.fulfill({ json: { ok: true, outbox_id: 42 } });
      return true;
    }
    if (!url.pathname.endsWith("/outbox")) return false;
    await route.fulfill({ json: { server_time: "2026-10-17T00:02:05Z", items:
      submitted && url.searchParams.get("status") === "all" ? [
        { id: 42, device: "phone", owner_wxid: "A", wxid: "friend", status: "sent", attempt_count: 1,
          text: "立即完成也能找到", created_at: "2026-10-17T00:02:00Z", updated_at: "2026-10-17T00:02:01Z" }
      ] : [] } });
    return true;
  });
  await page.locator("#inline-send").fill("立即完成也能找到");
  await page.getByRole("button", { name: "加入队列", exact: true }).click();
  const task = page.getByTestId("outbox-row").filter({ hasText: "任务 #42" });
  await expect(task.getByText("立即完成也能找到", { exact: true })).toBeVisible();
  await expect(task.getByText("微信已确认发送", { exact: true })).toBeVisible();
  await expect(page.locator("#inline-send")).toHaveValue("");
  await expect(page.getByText(/任务 #42 已创建/)).toBeVisible();
});

test("account generation changes reject delayed outbox contents", async ({ page }) => {
  let generation = 1;
  let delay = false;
  let held: Route | undefined;
  await setup(page, async (route, url) => {
    if (url.pathname.endsWith("/modules/status")) {
      await route.fulfill({ json: { modules: [{ device: "phone", device_wxid: "A", account_generation: generation, runtime_status: "ready" }] } });
      return true;
    }
    if (!url.pathname.endsWith("/outbox")) return false;
    if (delay) { delay = false; held = route; return true; }
    await route.fulfill({ json: { server_time: "2026-10-17T00:02:05Z", items: [
      { id: generation, device: "phone", owner_wxid: "A", wxid: "friend", status: "pending", attempt_count: 0, text: `会话代次 ${generation}` }
    ] } });
    return true;
  });
  await expect(page.getByText("会话代次 1", { exact: true })).toBeVisible();
  delay = true;
  await page.getByRole("button", { name: "刷新记录", exact: true }).click();
  await expect.poll(() => !!held).toBe(true);
  generation = 3;
  await page.getByRole("button", { name: "连接刷新", exact: true }).click();
  await expect(page.getByText("会话代次 3", { exact: true })).toBeVisible();
  await held!.fulfill({ json: { items: [{ id: 99, device: "phone", owner_wxid: "A", wxid: "friend", status: "pending", text: "过期队列正文" }], server_time: "2026-10-17T00:02:05Z" } });
  await expect(page.getByText("过期队列正文")).toHaveCount(0);
});

test("web connection is separate from server-reported phone availability", async ({ page }, testInfo) => {
  await setup(page, async (route, url) => {
    if (url.pathname.endsWith("/modules/status")) {
      await route.fulfill({ json: { modules: [{ device: "phone", device_wxid: "A", account_generation: 1,
        runtime_status: "offline", pending_outbox: 1, installations: [
          { id: 1, short_id: "current", active: true, state: "offline", device_model: "Samsung", android_version: "16", module_version: "0.1.21", switch_pending: false },
          { id: 2, short_id: "older", active: false, state: "offline", device_model: "Samsung", android_version: "16", module_version: "0.1.18", switch_pending: false }
        ] }] } });
      return true;
    }
    if (url.pathname.endsWith("/outbox")) {
      await route.fulfill({ json: { server_time: "2026-10-17T00:02:05Z", items: [
        { id: 8, device: "phone", owner_wxid: "A", wxid: "friend", status: "pending", attempt_count: 0,
          text: "等待恢复连接的内容", created_at: "2026-10-17T00:00:00Z", updated_at: "2026-10-17T00:00:00Z" }
      ] } });
      return true;
    }
    return false;
  }, true);
  await expect(page.getByText("网页已连接", { exact: true })).toBeVisible();
  await expect(page.getByText("实时已连接", { exact: true })).toHaveCount(0);
  await expect(page.getByText("待发送", { exact: true })).toHaveCount(0);
  await expect(page.getByText("离线", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("待发：1", { exact: true })).toBeVisible();
  await expect(page.getByText(/待处理任务需等待手机恢复连接/)).toBeVisible();
  await expect(page.getByText("等待恢复连接的内容", { exact: true })).toBeVisible();
  await expect(page.locator("#inline-send")).toBeDisabled();
  await page.screenshot({ path: testInfo.outputPath("offline-queue.png"), fullPage: true });
});
