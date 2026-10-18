import type { OutboxStatusItem } from "./types";

export function outboxResultUnconfirmed(item: Pick<OutboxStatusItem, "status" | "last_error">) {
  return item.status === "unknown" || (item.status === "failed" && !!item.last_error?.toLowerCase().includes("wechat message queued but delivery unconfirmed"));
}

export function outboxStatusLabel(status: string, lastError?: string) {
  if (outboxResultUnconfirmed({ status, last_error: lastError })) return "结果未确认";
  switch (status) {
    case "pending": return "等待微信领取";
    case "leased": return "已分派 · 待回执";
    case "native_started": return "发送处理中 · 待确认";
    case "sent": return "微信已确认发送";
    case "failed": return "发送失败";
    case "cancelled": return "已取消";
    default: return "状态未知";
  }
}

export function outboxIsActive(item: Pick<OutboxStatusItem, "status">) {
  return item.status === "pending" || item.status === "leased" || item.status === "native_started";
}

export function outboxWaitText(item: Pick<OutboxStatusItem, "status" | "created_at">, now: number) {
  if (!outboxIsActive(item) || !item.created_at) return "";
  const created = Date.parse(item.created_at);
  if (!Number.isFinite(created) || !Number.isFinite(now)) return "";
  const seconds = Math.floor(Math.max(0, now - created) / 1000);
  if (seconds < 60) return `已等待 ${seconds} 秒`;
  if (seconds < 3600) return `已等待 ${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
  return `已等待 ${Math.floor(seconds / 3600)} 小时 ${Math.floor(seconds % 3600 / 60)} 分`;
}

export function outboxLeaseExpired(item: Pick<OutboxStatusItem, "status" | "lease_until">, now: number) {
  return item.status === "leased" && !!item.lease_until && Date.parse(item.lease_until) <= now;
}
