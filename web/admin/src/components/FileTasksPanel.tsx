import * as React from "react";
import { FolderOpen, RefreshCw } from "lucide-react";
import { ApiError, cleanupFileTask, getFileTask, getFileTasks } from "@/api";
import { belongsToAccount } from "@/accountScope";
import { formatFileSize } from "@/fileSend";
import { parseApiError } from "@/installations";
import { outboxResultUnconfirmed, outboxStatusLabel } from "@/outbox";
import { formatBeijingDateTime } from "@/time";
import type { AdminSession } from "@/useAdminConnection";
import type { FileCleanupMode, FileTaskFilter, FileTaskView, ModuleContact } from "@/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { TabsList, TabsTrigger } from "@/components/ui/tabs";

type Props = {
  device: string; ownerWxid: string; enabled: boolean; contacts: Map<string, ModuleContact>;
  currentSession: () => AdminSession | null;
  handleError: (error: unknown, session: AdminSession) => void;
};

export function FileTasksPanel(props: Props) {
  const [open, setOpen] = React.useState(false);
  return <>
    <Button variant="outline" size="xs" onClick={() => setOpen(true)} disabled={!props.enabled}>
      <FolderOpen className="h-3 w-3" />文件暂存
    </Button>
    {open ? <FileTasksDialog {...props} onClose={() => setOpen(false)} /> : null}
  </>;
}

function FileTasksDialog({ device, ownerWxid, contacts, currentSession, handleError, onClose }: Props & { onClose: () => void }) {
  const [filter, setFilter] = React.useState<FileTaskFilter>("retained");
  const [items, setItems] = React.useState<FileTaskView[]>([]);
  const [nextBefore, setNextBefore] = React.useState<number | null>(null);
  const [busy, setBusy] = React.useState<{ id?: number; action: "list" | "refresh" | "cleanup" } | null>(null);
  const [error, setError] = React.useState("");
  const [rowMessages, setRowMessages] = React.useState<Record<number, string>>({});
  const mounted = React.useRef(false);
  const active = React.useRef<AbortController | null>(null);

  // One request at a time keeps refreshes from overwriting a cleanup result.
  // The parent keys this panel by account generation; unmount aborts all work.
  function begin(operation: NonNullable<typeof busy>) {
    const session = currentSession();
    if (!mounted.current || active.current || !session) return;
    const controller = new AbortController();
    const abort = () => controller.abort();
    session.signal.addEventListener("abort", abort, { once: true });
    if (session.signal.aborted) controller.abort();
    active.current = controller;
    setBusy(operation);
    return {
      session,
      params: { device, ownerWxid, password: session.password, signal: controller.signal },
      current: () => mounted.current && active.current === controller && !controller.signal.aborted && session.isCurrent(),
      finish: () => {
        session.signal.removeEventListener("abort", abort);
        if (active.current === controller) { active.current = null; if (mounted.current) setBusy(null); }
      }
    };
  }

  function valid(item: FileTaskView, id?: number) {
    return belongsToAccount(item, device, ownerWxid) && (id === undefined || item.id === id);
  }

  function replace(item: FileTaskView, id: number) {
    if (!valid(item, id)) throw new Error("任务与当前账号不匹配，请重新读取。");
    setItems(old => old.map(row => row.id === id ? item : row));
  }

  async function load(nextFilter: FileTaskFilter, beforeId?: number) {
    const request = begin({ action: "list" });
    if (!request) return;
    setFilter(nextFilter); setError("");
    if (beforeId === undefined) { setItems([]); setNextBefore(null); setRowMessages({}); }
    try {
      const response = await getFileTasks({ ...request.params, status: nextFilter, beforeId });
      if (!request.current()) return;
      const rows = response.items.filter(item => valid(item));
      setItems(old => beforeId === undefined ? rows : [...new Map([...old, ...rows].map(row => [row.id, row])).values()]);
      setNextBefore(response.next_before_id);
    } catch (cause) {
      if (!request.current()) return;
      handleError(cause, request.session);
      setError(`文件任务读取失败：${parseApiError(cause, "请稍后刷新").message}`);
    } finally { request.finish(); }
  }

  React.useEffect(() => {
    mounted.current = true;
    void load("retained");
    return () => { mounted.current = false; active.current?.abort(); active.current = null; };
  }, []);

  async function refresh(item: FileTaskView) {
    const request = begin({ action: "refresh", id: item.id });
    if (!request) return;
    try {
      const response = await getFileTask({ ...request.params, id: item.id });
      if (!request.current()) return;
      replace(response.item, item.id);
      setRowMessages(old => ({ ...old, [item.id]: "已重新读取任务状态，未触发发送。" }));
    } catch (cause) {
      if (!request.current()) return;
      handleError(cause, request.session);
      setRowMessages(old => ({ ...old, [item.id]: `状态读取失败：${parseApiError(cause, "请稍后重试").message}` }));
    } finally { request.finish(); }
  }

  async function cleanup(item: FileTaskView, mode: FileCleanupMode) {
    if (active.current || (mode === "server_only" ? !item.can_cleanup_server : !item.can_cancel_unstarted)) return;
    const explanation = mode === "cancel_unstarted"
      ? "取消此尚未提交微信的任务，并清理后端临时副本？取消后任务将不再发送。不会清理手机副本或撤回微信消息。"
      : "仅清理后端临时副本？不会撤回微信消息、清理手机副本、重新发送或改变发送结果。手机副本状态尚未上报。"
        + (outboxResultUnconfirmed(item) ? "此任务结果仍未确认，请先核查微信聊天记录。" : "");
    if (!window.confirm(explanation)) return;
    const request = begin({ action: "cleanup", id: item.id });
    if (!request) return;
    try {
      const response = await cleanupFileTask({ ...request.params, id: item.id, mode });
      if (!request.current()) return;
      replace(response.item, item.id);
      setRowMessages(old => ({ ...old, [item.id]: response.item.server_copy.state === "removed"
        ? "后端副本已清理；手机副本未上报，任务记录保留。"
        : "后端副本待清理，请刷新任务状态查看结果。" }));
    } catch (cause) {
      if (!request.current()) return;
      handleError(cause, request.session);
      let message = `清理未确认：${parseApiError(cause, "请刷新状态后重试").message}`;
      if (cause instanceof ApiError && cause.status === 409) {
        message = "任务状态已变化，本次未清理；请查看最新状态。";
        try {
          const response = await getFileTask({ ...request.params, id: item.id });
          if (!request.current()) return;
          replace(response.item, item.id);
        } catch (refreshError) {
          if (!request.current()) return;
          handleError(refreshError, request.session);
          message += "最新状态读取失败，请手动刷新。";
        }
      }
      if (request.current()) setRowMessages(old => ({ ...old, [item.id]: message }));
    } finally { request.finish(); }
  }

  return <Dialog open onOpenChange={open => { if (!open) onClose(); }}>
    <DialogContent role="dialog" aria-modal="true" aria-label="文件暂存" onClose={onClose}
      className="max-h-[calc(100dvh-2rem)] max-w-3xl overflow-y-auto">
      <DialogHeader>
        <DialogTitle>文件暂存</DialogTitle>
        <DialogDescription>查看当前微信的文件任务。刷新只读取状态，不会重新发送；清理不会删除微信聊天中的附件。</DialogDescription>
      </DialogHeader>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <TabsList className="h-auto flex-wrap">
          {([['retained', '后端有副本'], ['unconfirmed', '未确认'], ['all', '全部文件']] as const).map(([value, title]) =>
            <TabsTrigger key={value} active={filter === value} disabled={!!busy} onClick={() => void load(value)}>{title}</TabsTrigger>)}
        </TabsList>
        <Button size="sm" variant="outline" disabled={!!busy} onClick={() => void load(filter)}><RefreshCw className="h-3 w-3" />刷新列表</Button>
      </div>
      <p className="mt-2 text-xs text-muted-foreground">按任务从新到旧分页，每页 50 条。未确认任务可在“未确认”中继续查看，清理后端副本不会隐藏其结果。</p>
      {error ? <p role="alert" className="mt-3 text-sm text-destructive">{error}</p> : null}
      {!items.length && !error ? <p className="py-5 text-sm text-muted-foreground">{busy ? "正在读取文件任务…" : "此筛选下暂无文件任务"}</p> : null}
      <ul className="mt-3 grid min-w-0 gap-3">
        {items.map(item => {
          const unconfirmed = outboxResultUnconfirmed(item);
          const mode: FileCleanupMode | undefined = item.can_cleanup_server ? "server_only" : item.can_cancel_unstarted ? "cancel_unstarted" : undefined;
          const contact = contacts.get(item.wxid);
          const copyState = item.server_copy.state === "removed" ? "已清理" : item.server_copy.state === "cleanup_pending" ? "待清理" : "有副本";
          const blocked = item.cleanup_blocked_reason ? cleanupReason(item.cleanup_blocked_reason) : "当前不可清理，请刷新任务状态。";
          return <li key={item.id} data-testid="file-task-row" className="min-w-0 rounded-md border p-3 text-xs">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="break-all font-medium">任务 #{item.id} · {item.attachment?.name || "文件信息暂不可用"}</span>
              <Badge variant={unconfirmed ? "warning" : item.status === "sent" ? "success" : "secondary"}>{fileStageLabel(item)}</Badge>
            </div>
            <p className="mt-2 break-all">{contact?.remark || contact?.nickname || item.wxid} · {item.attachment ? formatFileSize(item.attachment.size) : "大小未知"}</p>
            <p className="mt-1">后端副本：{copyState}</p>
            <p className="mt-1 text-muted-foreground">上方为最后确认的阶段，不表示当前传输进度。</p>
            {unconfirmed ? <p className="mt-2 text-amber-700 dark:text-amber-200">发送结果未确认。只清理后端副本不会确认结果或撤回消息，请核查微信聊天记录，不要重新创建发送任务。</p> : null}
            <details className="mt-2">
              <summary className="cursor-pointer font-medium">任务详情</summary>
              <div className="mt-2 grid gap-1 break-words text-muted-foreground">
                <p>创建：{formatBeijingDateTime(item.created_at)} · 更新：{formatBeijingDateTime(item.updated_at)}</p>
                <p>发放一次发送许可：{item.native_started_at ? formatBeijingDateTime(item.native_started_at) : "尚无许可记录"}</p>
                {item.lease_until ? <p>租约截止：{formatBeijingDateTime(item.lease_until)}（非预计送达时间）</p> : null}
                <p>分派 {item.attempt_count} 次{item.chat_record_id ? ` · 微信本地消息 #${item.chat_record_id}` : ""}</p>
                <p>最后错误：{item.last_error || "无"}</p>
                <p>手机副本：未上报</p>
                <p>手机临时副本和微信原生附件不在本页清理范围内，不能根据任务结果判断已删除。</p>
                {item.server_copy.cleanup_requested_at ? <p>清理请求：{formatBeijingDateTime(item.server_copy.cleanup_requested_at)}</p> : null}
                {item.server_copy.updated_at ? <p>后端副本更新：{formatBeijingDateTime(item.server_copy.updated_at)}</p> : null}
              </div>
            </details>
            {item.server_copy.last_error ? <p role="alert" className="mt-2 text-destructive">后端清理失败：{cleanupReason(item.server_copy.last_error)} 可重试清理。</p> : null}
            {item.server_copy.state !== "removed" && !mode ? <p className="mt-2 text-muted-foreground">{blocked}</p> : null}
            {rowMessages[item.id] ? <p role="status" className="mt-2">{rowMessages[item.id]}</p> : null}
            <div className="mt-3 flex flex-wrap gap-2">
              <Button size="xs" variant="outline" disabled={!!busy} onClick={() => void refresh(item)}>{busy?.id === item.id && busy.action === "refresh" ? "正在读取…" : "刷新任务状态"}</Button>
              {item.server_copy.state !== "removed" ? <Button size="xs" variant="outline" disabled={!!busy || !mode}
                onClick={() => { if (mode) void cleanup(item, mode); }}>
                {busy?.id === item.id && busy.action === "cleanup" ? "正在请求清理…"
                  : mode === "cancel_unstarted" ? "取消未提交任务并清理后端"
                    : item.server_copy.last_error ? "重试清理" : "清理后端副本"}
              </Button> : null}
            </div>
          </li>;
        })}
      </ul>
      {nextBefore !== null ? <Button className="mt-4" size="sm" variant="outline" disabled={!!busy} onClick={() => void load(filter, nextBefore)}>加载更早任务</Button> : null}
    </DialogContent>
  </Dialog>;
}

function cleanupReason(code: string) {
  const reasons: Record<string, string> = {
    active_lease: "手机的领取租约仍有效，暂不能清理，待任务更新后刷新。",
    already_removed: "后端副本已清理。",
    cleanup_pending: "清理请求已登记，等待后端完成。",
    missing_native_grant: "尚未确认发送许可，不能安全清理后端副本。",
    unsupported_state: "当前任务状态不支持清理。",
    unstarted_task: "此任务尚未提交微信，需要先取消任务才能清理。",
    remove_failed: "后端暂时无法删除文件。",
    storage_unavailable: "后端文件存储暂不可用。",
    record_failed: "文件清理结果尚未记入后端，请刷新状态后重试。"
  };
  return reasons[code] || `当前清理状态：${code}`;
}

function fileStageLabel(item: FileTaskView) {
  switch (item.status) {
    case "pending": return "待领取";
    case "leased": return "已领取 · 尚未获微信发送许可";
    case "native_started": return "已发放一次许可 · 等待结果";
    case "unknown": return "手机报告结果未确认";
    default: return outboxStatusLabel(item.status, item.last_error);
  }
}
