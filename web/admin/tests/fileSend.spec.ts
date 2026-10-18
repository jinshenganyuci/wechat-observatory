import { test, expect, type Page, type Route } from "@playwright/test";

const report = { name: "report.txt", mimeType: "text/plain", buffer: Buffer.from("synthetic notification attachment") };

async function setup(page: Page, handle: (route: Route, url: URL) => Promise<boolean>) {
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
    if (await handle(route, url)) return;
    if (url.pathname.endsWith("/modules/status")) return route.fulfill({ json: { modules: [{
      device: "phone", device_wxid: "owner", account_generation: 7, runtime_status: "ready"
    }] } });
    if (url.pathname.endsWith("/api-keys")) return route.fulfill({ json: { api_keys: [] } });
    if (url.pathname.endsWith("/module-contacts")) return route.fulfill({ json: { contacts: [
      { id: 1, device: "phone", owner_wxid: "owner", wxid: "notice@chatroom", remark: "通知群", chatroom: true, deleted: false },
      { id: 2, device: "phone", owner_wxid: "owner", wxid: "friend", remark: "其他联系人", chatroom: false, deleted: false }
    ] } });
    if (url.pathname.endsWith("/messages")) return route.fulfill({ json: { messages: [] } });
    if (url.pathname.endsWith("/outbox")) return route.fulfill({ json: { items: [], server_time: "2026-10-18T12:00:00Z" } });
    return route.fulfill({ status: 404, json: {} });
  });
  await page.goto("/");
  await expect(page.locator("#inline-send")).toBeEnabled();
}

async function openFiles(page: Page) {
  await page.getByRole("button", { name: "发消息", exact: true }).click();
  await page.getByRole("button", { name: "文件", exact: true }).click();
}

test("one multipart upload creates a traceable file task, never an early sent claim", async ({ page }, testInfo) => {
  const requests: Route[] = [];
  let status = "native_started";
  let accepted = false;
  await setup(page, async (route, url) => {
    if (url.pathname.endsWith("/send/file")) { requests.push(route); return true; }
    if (url.pathname.endsWith("/outbox") && accepted) {
      await route.fulfill({ json: { server_time: "2026-10-18T12:00:00Z", items: [{
        id: 71, device: "phone", owner_wxid: "owner", wxid: "notice@chatroom", kind: "file",
        attachment: { id: "mock-attachment", name: report.name, size: report.buffer.length, sha256: "mock-hash" },
        status, attempt_count: 1
      }] } });
      return true;
    }
    return false;
  });
  await openFiles(page);
  await page.getByLabel("选择文件", { exact: true }).setInputFiles(report);
  await expect(page.getByTestId("file-draft-name")).toHaveText("report.txt");
  const submit = page.getByRole("button", { name: "加入队列", exact: true }).last();
  await submit.evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
  await expect.poll(() => requests.length).toBe(1);
  await expect(page.getByText("正在上传文件并创建任务，请勿重复提交。", { exact: true })).toBeVisible();
  const request = requests[0].request();
  expect(request.headers()["idempotency-key"]).toMatch(/^file-[a-f0-9]{32}$/);
  expect(request.headers()["x-bridge-password"]).toBe("mock-password");
  expect(request.headers()["content-type"]).toContain("multipart/form-data; boundary=");
  const body = request.postDataBuffer()!.toString();
  for (const [name, value] of Object.entries({ device: "phone", owner_wxid: "owner", account_generation: "7", wxid: "notice@chatroom" })) {
    expect(body).toContain(`name="${name}"\r\n\r\n${value}\r\n`);
  }
  expect(body).toContain('name="file"; filename="report.txt"');
  expect(body).toContain(report.buffer.toString());
  await page.screenshot({ path: testInfo.outputPath("file-submit.png"), fullPage: true });
  accepted = true;
  await requests[0].fulfill({ json: { ok: true, outbox_id: 71, idempotent_replay: false } });
  const task = page.getByTestId("outbox-row").filter({ hasText: "任务 #71" });
  await expect(task.getByText("文件：report.txt", { exact: true })).toBeVisible();
  await expect(task.getByText("发送处理中 · 待确认", { exact: true })).toBeVisible();
  await expect(task.getByText("微信已确认发送", { exact: true })).toHaveCount(0);
  status = "unknown";
  await page.getByRole("button", { name: "刷新记录", exact: true }).click();
  await expect(task.getByText("结果未确认", { exact: true })).toBeVisible();
  await expect(task.getByText(/不要重新创建发送任务/)).toBeVisible();
  expect(requests).toHaveLength(1);
  status = "sent";
  await page.getByRole("button", { name: "刷新记录", exact: true }).click();
  await expect(task.getByText("微信已确认发送", { exact: true })).toBeVisible();
});

test("uncertain upload survives reload and retries only with the original key and payload", async ({ page }) => {
  const keys: string[] = [];
  const bodies: string[] = [];
  let generation = 7;
  await setup(page, async (route, url) => {
    if (url.pathname.endsWith("/modules/status")) {
      await route.fulfill({ json: { modules: [{ device: "phone", device_wxid: "owner",
        account_generation: generation, runtime_status: "ready" }] } }); return true;
    }
    if (!url.pathname.endsWith("/send/file")) return false;
    keys.push(route.request().headers()["idempotency-key"]);
    bodies.push(route.request().postDataBuffer()!.toString());
    if (keys.length === 1) await route.abort("failed");
    else await route.fulfill({ json: { ok: true, outbox_id: 72, idempotent_replay: true } });
    return true;
  });
  await openFiles(page);
  await page.getByLabel("选择文件", { exact: true }).setInputFiles(report);
  await page.getByRole("button", { name: "加入队列", exact: true }).last().click();
  await expect(page.getByRole("button", { name: "使用原请求重试", exact: true })).toBeEnabled();
  generation = 8;
  await page.reload();
  await expect(page.locator("#inline-send")).toBeEnabled();
  await openFiles(page);
  await expect(page.getByTestId("file-draft-name")).toHaveText(report.name);
  await expect(page.getByRole("button", { name: "使用原请求重试", exact: true })).toBeDisabled();
  expect(keys).toHaveLength(1);
  await page.getByLabel("选择文件", { exact: true }).setInputFiles({ ...report, name: "different.txt" });
  await expect(page.getByText(/文件名和大小必须一致/)).toBeVisible();
  await page.getByLabel("选择文件", { exact: true }).setInputFiles(report);
  await page.getByRole("button", { name: "使用原请求重试", exact: true }).click();
  await expect(page.getByText("文件任务 #72 已存在，未创建新发送；请查看发送记录")).toBeVisible();
  expect(keys).toHaveLength(2);
  expect(keys[1]).toBe(keys[0]);
  expect(bodies.every(body => body.includes('name="account_generation"\r\n\r\n7\r\n'))).toBe(true);
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem("wgc_pending_file_request"))).toBeNull();
});

test("an uncertain file cannot move to another chat and cancelling replacement retains its identity", async ({ page }) => {
  const keys: string[] = [];
  await setup(page, async (route, url) => {
    if (!url.pathname.endsWith("/send/file")) return false;
    keys.push(route.request().headers()["idempotency-key"]);
    await route.abort("failed"); return true;
  });
  await openFiles(page);
  await page.getByLabel("选择文件", { exact: true }).setInputFiles(report);
  await page.getByRole("button", { name: "加入队列", exact: true }).last().click();
  await expect(page.getByRole("button", { name: "使用原请求重试", exact: true })).toBeEnabled();
  page.once("dialog", dialog => dialog.dismiss());
  await page.getByRole("button", { name: "取消选择", exact: true }).click();
  await expect(page.getByTestId("file-draft-name")).toHaveText(report.name);
  await page.getByRole("button", { name: "关闭", exact: true }).click();
  await page.getByRole("button").filter({ hasText: "其他联系人" }).click();
  await openFiles(page);
  await expect(page.getByText(/不能发送到当前会话/)).toBeVisible();
  await expect(page.getByRole("button", { name: "使用原请求重试", exact: true })).toBeDisabled();
  expect(keys).toHaveLength(1);
});

test("empty, oversized, unsupported and invalidly named files are rejected before a request", async ({ page }) => {
  let sends = 0;
  await setup(page, async (route, url) => {
    if (!url.pathname.endsWith("/send/file")) return false;
    sends++; await route.fulfill({ json: { ok: true, outbox_id: 1 } }); return true;
  });
  await openFiles(page);
  const input = page.getByLabel("选择文件", { exact: true });
  await input.setInputFiles({ name: "empty.txt", mimeType: "text/plain", buffer: Buffer.alloc(0) });
  await expect(page.getByRole("alert")).toHaveText("请选择非空文件。");
  await input.setInputFiles({ name: "x".repeat(237) + ".txt", mimeType: "text/plain", buffer: Buffer.from("mock") });
  await expect(page.getByRole("alert")).toHaveText(/文件名不能超过 240 UTF-8 字节/);
  await input.setInputFiles({ name: "photo.JPG", mimeType: "image/jpeg", buffer: Buffer.from("mock") });
  await expect(page.getByRole("alert")).toHaveText(/暂不支持图片或视频/);
  await input.setInputFiles({ name: "large.zip", mimeType: "application/zip", buffer: Buffer.alloc(25 * 1024 * 1024 + 1) });
  await expect(page.getByRole("alert")).toHaveText("文件不能超过 25 MiB。");
  await expect(page.getByRole("button", { name: "加入队列", exact: true }).last()).toBeDisabled();
  expect(sends).toBe(0);
});
