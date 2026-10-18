import { RefreshCw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { TabsList, TabsTrigger } from "@/components/ui/tabs";
import { formatBeijingDateTime } from "@/time";
import { formatFileSize } from "@/fileSend";
import { outboxIsActive, outboxLeaseExpired, outboxResultUnconfirmed, outboxStatusLabel, outboxWaitText } from "@/outbox";
import type { ModuleContact, OutboxFilter, OutboxStatusItem } from "@/types";

export function OutboxList({ items, filter, onFilter, chatOnly, onChatOnly, hasChat, now, error, loading, enabled, offline, onRefresh, contacts, submittedId }: {
  items: OutboxStatusItem[]; filter: OutboxFilter; onFilter: (value: OutboxFilter) => void;
  chatOnly: boolean; onChatOnly: (value: boolean) => void; hasChat: boolean; now: number;
  error: string; loading: boolean; enabled: boolean; offline: boolean; onRefresh: () => void;
  contacts: Map<string, ModuleContact>; submittedId?: number;
}) {
  return <section id="send-records" className="grid min-w-0 scroll-mt-32 gap-3 rounded-lg border bg-card p-3" aria-label="发送记录">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h3 className="text-sm font-semibold">发送记录</h3>
      <Button variant="outline" size="xs" onClick={onRefresh} disabled={!enabled || loading}>
        <RefreshCw className={loading ? "h-3 w-3 animate-spin" : "h-3 w-3"} />刷新记录
      </Button>
    </div>
    <div className="flex flex-wrap items-center justify-between gap-2">
      <TabsList className="h-auto flex-wrap">
        {([['active', '待处理'], ['failed', '失败 / 未确认'], ['all', '最近记录']] as const).map(([value, title]) =>
          <TabsTrigger key={value} active={filter === value} onClick={() => onFilter(value)}>{title}</TabsTrigger>)}
      </TabsList>
      <label className="flex items-center gap-1 text-xs"><Checkbox checked={chatOnly} disabled={!hasChat}
        onChange={(event) => onChatOnly(event.currentTarget.checked)} />仅当前会话</label>
    </div>
    <p className="text-xs text-muted-foreground">{chatOnly && hasChat ? "当前会话" : "当前微信全部会话"} · {filter === "active" ? "待处理按提交先后，最多显示 50 条" : "最多显示最近 50 条"}。收到微信回执后才确认发送。</p>
    {offline ? <p className="text-xs text-amber-700 dark:text-amber-200">当前手机离线。待处理任务需等待手机恢复连接，暂无确定完成时间；已分派任务请以回执或聊天记录核实结果。</p> : null}
    {error ? <p role="alert" className="text-xs text-destructive">{error}</p> : null}
    {!enabled ? <p className="text-xs text-muted-foreground">连接并选择已注册的微信后查看。</p> : !items.length && !error ?
      <p className="text-xs text-muted-foreground">{loading ? "正在读取发送记录…" : "此筛选下暂无发送记录"}</p> : null}
    <ul className="grid max-h-[420px] gap-2 overflow-y-auto">
      {items.map((item) => {
        const contact = contacts.get(item.wxid);
        const name = contact?.remark || contact?.nickname || item.wxid;
        const unconfirmed = outboxResultUnconfirmed(item);
        const tone = item.status === "sent" ? "success" : unconfirmed ? "warning" : item.status === "failed" ? "destructive" : outboxIsActive(item) ? "warning" : "secondary";
        const wait = outboxWaitText(item, now);
        return <li key={item.id} data-testid="outbox-row" className={`min-w-0 rounded-md border p-3 text-xs ${submittedId === item.id ? "border-primary bg-secondary/40" : ""}`}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="break-all font-medium">{name}{name !== item.wxid ? `（${item.wxid}）` : ""}</span>
            <Badge variant={tone}>{outboxStatusLabel(item.status, item.last_error)}</Badge>
          </div>
          {item.kind === "file" ? <div className="mt-2 min-w-0 text-sm">
            <p className="break-all">文件：{item.attachment?.name || "文件信息暂不可用"}</p>
            {item.attachment ? <p className="mt-1 text-xs text-muted-foreground">{formatFileSize(item.attachment.size)}</p> : null}
          </div> : (item.text || "").length > 240 ? <details className="mt-2"><summary className="cursor-pointer whitespace-pre-wrap break-words">{item.text.slice(0, 120)}… 展开全文</summary>
            <p className="mt-2 whitespace-pre-wrap break-words text-sm">{item.text}</p></details>
            : <p className="mt-2 whitespace-pre-wrap break-words text-sm">{item.text}</p>}
          <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-muted-foreground">
            <span>任务 #{item.id}</span><span>创建 {formatBeijingDateTime(item.created_at)}</span>
            <span>状态更新 {formatBeijingDateTime(item.updated_at)}</span>{wait ? <span>{wait}</span> : null}
          </div>
          {item.status === "leased" && item.lease_until ? <p className="mt-1 text-muted-foreground">
            {outboxLeaseExpired(item, now) ? "已超时待恢复" : `超时恢复参考 ${formatBeijingDateTime(item.lease_until)}`}
            <span> · 不代表预计送达时间</span>
          </p> : null}
          {item.attempt_count > 0 ? <p className="mt-1 text-muted-foreground">分派 {item.attempt_count} 次</p> : null}
          {unconfirmed ? <p className="mt-2 text-amber-700 dark:text-amber-200">{item.kind === "file" ? "文件发送结果未确认；请先查看微信聊天记录，不要重新创建发送任务。" : "微信已接收任务，但尚未确认结果；请先查看聊天记录，避免重复发送。"}</p> : null}
          {item.last_error ? <p className="mt-2 whitespace-pre-wrap break-words text-destructive">{item.last_error}</p> : null}
        </li>;
      })}
    </ul>
  </section>;
}
