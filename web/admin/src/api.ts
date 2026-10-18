import type { ApiKey, LiveMessageEvent, ModuleContact, ModuleStatus, ModuleSwitchRequest, StoredMessage, OutboxStatusItem, OutboxFilter } from "@/types";

type ApiOptions = {
  password: string;
  method?: "GET" | "POST" | "DELETE";
  body?: unknown;
  timeoutMs?: number;
  signal?: AbortSignal;
};

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

const OBSERVATORY_MOUNT = "/observatory";

export function publicPath(path: string) {
  if (!path.startsWith("/")) {
    throw new Error("public path must start with /");
  }
  const mounted = window.location.pathname === OBSERVATORY_MOUNT
    || window.location.pathname.startsWith(`${OBSERVATORY_MOUNT}/`);
  return `${mounted ? OBSERVATORY_MOUNT : ""}${path}`;
}

async function requestJSON<T>(path: string, options: ApiOptions): Promise<T> {
  const headers: Record<string, string> = { "X-Bridge-Password": options.password };
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal?.addEventListener("abort", abort, { once: true });
  if (options.signal?.aborted) controller.abort();
  const timeoutMs = options.timeoutMs ?? ((options.method ?? "GET") === "GET" ? 10000 : undefined);
  const timeout = timeoutMs ? window.setTimeout(() => controller.abort(), timeoutMs) : undefined;
  try {
    const response = await fetch(publicPath(path), {
      method: options.method ?? "GET", headers, signal: controller.signal,
      body: options.body === undefined ? undefined : JSON.stringify(options.body)
    });
    const text = await response.text();
    if (!response.ok) throw new ApiError(response.status, text || `HTTP ${response.status}`);
    return JSON.parse(text) as T;
  } finally {
    if (timeout !== undefined) window.clearTimeout(timeout);
    options.signal?.removeEventListener("abort", abort);
  }
}

export async function getModules(password: string, signal?: AbortSignal) {
  return requestJSON<{ modules: ModuleStatus[] }>("/api/modules/status", { password, signal, timeoutMs: 10000 });
}

/** Ask the server to hand the device binding to another phone on its next heartbeat. */
export async function switchModuleInstallation(params: { password: string; device: string; installationId: number }) {
  return requestJSON<{ ok: boolean; switch_request?: ModuleSwitchRequest }>(
    `/api/modules/${encodeURIComponent(params.device)}/switch`,
    {
      password: params.password,
      method: "POST",
      body: { installation_id: params.installationId }
    }
  );
}

export async function getApiKeys(password: string) {
  return requestJSON<{ api_keys?: ApiKey[] }>("/api/api-keys?limit=200", { password });
}

export async function createApiKey(params: {
  password: string;
  apiKey?: string;
  device?: string;
  nickname?: string;
}) {
  return requestJSON<{ ok: boolean; api_key?: ApiKey }>("/api/api-keys", {
    password: params.password,
    method: "POST",
    body: {
      api_key: params.apiKey,
      device: params.device,
      nickname: params.nickname
    }
  });
}

export async function deleteApiKey(params: { password: string; apiKey: string }) {
  return requestJSON<{ ok: boolean }>(`/api/api-keys/${encodeURIComponent(params.apiKey)}`, {
    password: params.password,
    method: "DELETE"
  });
}

export async function setApiKeyEnabled(params: { password: string; apiKey: string; enabled: boolean }) {
  return requestJSON<{ ok: boolean; api_key?: ApiKey }>(
    `/api/api-keys/${encodeURIComponent(params.apiKey)}/${params.enabled ? "enable" : "disable"}`,
    {
      password: params.password,
      method: "POST"
    }
  );
}

export async function updateDevice(params: { password: string; name: string; nickname?: string }) {
  return requestJSON<{ ok: boolean; device: ModuleStatus }>("/api/devices", {
    password: params.password,
    method: "POST",
    body: {
      name: params.name,
      nickname: params.nickname
    }
  });
}

export async function getContacts(params: {
  password: string;
  device: string;
  ownerWxid?: string;
  query: string;
  includeDeleted: boolean;
  limit?: number;
}) {
  const search = new URLSearchParams({
    device: params.device,
    limit: String(params.limit ?? 300)
  });
  if (params.query.trim()) {
    search.set("q", params.query.trim());
  }
  if (params.ownerWxid?.trim()) {
    search.set("owner_wxid", params.ownerWxid.trim());
  }
  if (params.includeDeleted) {
    search.set("include_deleted", "1");
  }
  return requestJSON<{ contacts: ModuleContact[] }>(`/api/module-contacts?${search}`, {
    password: params.password
  });
}

export async function getMessages(params: {
  password: string;
  device: string;
  wxid?: string;
  ownerWxid?: string;
  chatId?: string;
  chatKind?: string;
  limit?: number;
}) {
  const search = new URLSearchParams({
    device: params.device,
    limit: String(params.limit ?? 120)
  });
  if (params.chatId) {
    search.set("chat_id", params.chatId);
  }
  if (params.ownerWxid?.trim()) {
    search.set("owner_wxid", params.ownerWxid.trim());
  }
  if (params.chatKind) {
    search.set("chat_kind", params.chatKind);
  }
  if (params.wxid) {
    search.set("wxid", params.wxid);
  }
  return requestJSON<{ messages: StoredMessage[] }>(`/api/messages?${search}`, {
    password: params.password
  });
}

export async function sendText(params: {
  accountGeneration: number;
  password: string;
  device: string;
  ownerWxid: string;
  wxid: string;
  text: string;
}) {
  return requestJSON<{ ok: boolean; outbox_id?: number }>("/api/send/text", {
    password: params.password,
    method: "POST",
    timeoutMs: 20000,
    body: {
      device: params.device,
      owner_wxid: params.ownerWxid,
      account_generation: params.accountGeneration,
      wx_ids: [params.wxid],
      text: params.text
    }
  });
}

/** The browser owns the multipart boundary; callers retain the key across uncertain replies. */
export async function sendFile(params: {
  password: string; device: string; ownerWxid: string; accountGeneration: number;
  wxid: string; file: File; idempotencyKey: string;
}) {
  const body = new FormData();
  body.set("device", params.device);
  body.set("owner_wxid", params.ownerWxid);
  body.set("account_generation", String(params.accountGeneration));
  body.set("wxid", params.wxid);
  body.set("file", params.file, params.file.name);
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 20 * 60 * 1000);
  try {
    const response = await fetch(publicPath("/api/send/file"), {
      method: "POST", body, signal: controller.signal,
      headers: { "X-Bridge-Password": params.password, "Idempotency-Key": params.idempotencyKey }
    });
    const text = await response.text();
    if (!response.ok) throw new ApiError(response.status, text || `HTTP ${response.status}`);
    return JSON.parse(text) as { ok: boolean; outbox_id?: number; idempotent_replay?: boolean };
  } finally { window.clearTimeout(timeout); }
}

export async function getOutbox(params: {
  password: string; device: string; ownerWxid: string; wxid?: string; status: OutboxFilter;
}) {
  const search = new URLSearchParams({ device: params.device, owner_wxid: params.ownerWxid, status: params.status, limit: "50" });
  if (params.wxid) search.set("wxid", params.wxid);
  return requestJSON<{ items: OutboxStatusItem[]; server_time: string }>(`/api/outbox?${search}`, { password: params.password, timeoutMs: 10000 });
}

export function openLiveEvents(password: string) {
  const search = new URLSearchParams({ password });
  return new EventSource(publicPath(`/api/live/events?${search}`));
}

export function parseLiveMessageEvent(raw: string) {
  return JSON.parse(raw) as LiveMessageEvent;
}
