import { AccountScope, belongsToAccount } from "./accountScope";
import { useAdminConnection, type AdminSession } from "./useAdminConnection";
import { clearFileRequest, fileDraftMatchesScope, fileTargetSupported, fileValidationError, formatFileSize, newFileRequestKey, persistFileRequest, restoreFileRequest, type FileSendDraft } from "./fileSend";
import React from "react";
import { createRoot } from "react-dom/client";
import { formatBeijingClock, formatBeijingDateTime, formatBeijingTimeAgo } from "@/time";
import {
  Activity,
  LayoutDashboard,
  LogOut,
  ChevronRight,
  Download,
  FileText,
  Hash,
  ImageIcon,
  KeyRound,
  MessageCircle,
  Mic,
  Moon,
  Paperclip,
  PauseCircle,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Smartphone,
  PlayCircle,
  Sun,
  Trash2,
  UserRound,
  Users,
  Video,
  Wifi,
  WifiOff
} from "lucide-react";
import {
  createApiKey,
  ApiError,
  deleteApiKey,
  getContacts,
  getApiKeys,
  getMessages,
  getOutbox,
  publicPath,
  setApiKeyEnabled,
  sendText,
  sendFile,
  switchModuleInstallation,
  updateDevice
} from "@/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { OutboxList } from "@/components/OutboxList";
import { InstallationList, InstallationSwitchHint, type InstallationNotice } from "@/components/InstallationList";
import { INSTALLATION_ALREADY_ACTIVE, INSTALLATION_SWITCH_REQUESTED, installationDisplayName, installationIsStandby, parseApiError } from "@/installations";
import type { ApiKey, LiveMessageEvent, ModuleContact, ModuleInstallation, ModuleStatus, StoredMessage, OutboxStatusItem, OutboxFilter } from "@/types";
import "./index.css";

const THEME_KEY = "wgc_admin_theme";
const RAW_PROVIDER_MODULE_ACK = "module_ack";
const CONTACT_SNAPSHOT_LIMIT = 10000;

type ContactFilter = "all" | "direct" | "room" | "messages";
type ThemeMode = "light" | "dark";

function resolveTheme(): ThemeMode {
  const stored = localStorage.getItem(THEME_KEY);
  if (stored === "light" || stored === "dark") {
    return stored;
  }
  return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function App() {
  const [password, setPassword] = React.useState("");
  const [panel, setPanel] = React.useState<"workspace" | "devices" | "access">("workspace");
  const [theme, setTheme] = React.useState<ThemeMode>(() => resolveTheme());
  const [modules, setModules] = React.useState<ModuleStatus[]>([]);
  const [apiKeys, setApiKeys] = React.useState<ApiKey[]>([]);
  const [contacts, setContacts] = React.useState<ModuleContact[]>([]);
  const [messages, setMessages] = React.useState<StoredMessage[]>([]);
  const [recentMessages, setRecentMessages] = React.useState<StoredMessage[]>([]);
  const [selectedDevice, setSelectedDevice] = React.useState("");
  const [selectedWxid, setSelectedWxid] = React.useState("");
  const [query, setQuery] = React.useState("");
  const [includeDeleted, setIncludeDeleted] = React.useState(false);
  const [contactFilter, setContactFilter] = React.useState<ContactFilter>("all");
  const [autoRefresh, setAutoRefresh] = React.useState(true);
  const [sendOpen, setSendOpen] = React.useState(false);
  const [sendMode, setSendMode] = React.useState<"text" | "file">("text");
  const [fileDraft, setFileDraft] = React.useState<FileSendDraft | null>(() => restoreFileRequest());
  const [fileError, setFileError] = React.useState("");
  const [draft, setDraft] = React.useState("");
  const [sending, setSending] = React.useState(false);
  const sendInFlight = React.useRef(false);
  const [outboxItems, setOutboxItems] = React.useState<OutboxStatusItem[]>([]);
  const [outboxFilter, setOutboxFilter] = React.useState<OutboxFilter>("active");
  const [outboxChatOnly, setOutboxChatOnly] = React.useState(false);
  const [outboxNow, setOutboxNow] = React.useState(Date.now());
  const [outboxError, setOutboxError] = React.useState("");
  const [outboxLoading, setOutboxLoading] = React.useState(false);
  const [submittedOutboxId, setSubmittedOutboxId] = React.useState<number>();
  const [savingAdmin, setSavingAdmin] = React.useState(false);
  const [newApiKey, setNewApiKey] = React.useState("");
  const [newApiKeyDevice, setNewApiKeyDevice] = React.useState("");
  const [newApiKeyNickname, setNewApiKeyNickname] = React.useState("");
  const [deviceNicknameDraft, setDeviceNicknameDraft] = React.useState("");
  const [notice, setNotice] = React.useState("输入管理密码后连接");
  const [switchingInstallation, setSwitchingInstallation] = React.useState<{ device: string; id: number } | null>(null);
  const [installationNotice, setInstallationNotice] = React.useState<(InstallationNotice & { device: string }) | null>(null);

  const connection = useAdminConnection({
    autoRefresh,
    onAuthenticated: (nextModules) => {
      applyModules(nextModules);
      if (!adminPassword) setNotice(`已同步 ${formatBeijingClock()}`);
    },
    onRefresh: async (session) => {
      await Promise.all([
        refreshApiKeys(),
        refreshContacts(deviceRef.current),
        refreshRecentMessages(deviceRef.current),
        refreshMessages(deviceRef.current, selectedWxid),
        refreshOutboxRef.current()
      ]);
      if (session.isCurrent()) setNotice(`已同步 ${formatBeijingClock()}`);
    },
    onMessage: async (payload, session) => {
      if (payload.device !== deviceRef.current) return;
      await refreshModules();
      if (!session.isCurrent()) return;
      if (!belongsToAccount(payload, selectedDevice, selectedOwnerWxid) || payload.raw_provider === RAW_PROVIDER_MODULE_ACK) return;
      await Promise.all([
        refreshContacts(selectedDevice, selectedOwnerWxid),
        refreshRecentMessages(selectedDevice, selectedOwnerWxid),
        selectedWxid && liveEventTouchesChat(payload, selectedWxid)
          ? refreshMessages(selectedDevice, selectedWxid, selectedOwnerWxid) : Promise.resolve()
      ]);
    },
    onReset: () => {
      accountScope.current.update("", "", 0);
      setModules([]); setApiKeys([]); setContacts([]); setMessages([]); setRecentMessages([]);
      setSelectedDevice(""); setSelectedWxid(""); setOutboxItems([]); setDraft("");
      setNewApiKey(""); setSendOpen(false); setSending(false); setSavingAdmin(false);
      setFileDraft(restoreFileRequest()); setFileError(""); setSendMode("text");
      setInstallationNotice(null); setSwitchingInstallation(null);
      setNotice("输入管理密码，进入你的微信工作台");
    }
  });
  const { password: adminPassword, liveConnected, connecting: loading, currentSession, handleError } = connection;
  const selectedModule = React.useMemo(
    () => modules.find((item) => item.device === selectedDevice),
    [modules, selectedDevice]
  );
  const selectedOwnerWxid = moduleOwnerWxid(selectedModule);
  const selectedGeneration = selectedModule?.account_generation ?? 0;
  const selectedScopeKey = `${selectedDevice}:${selectedOwnerWxid}:${selectedGeneration}`;
  const fileScope = { device: selectedDevice, ownerWxid: selectedOwnerWxid, generation: selectedGeneration, wxid: selectedWxid };
  const fileScopeMatches = !!fileDraft && fileDraftMatchesScope(fileDraft, fileScope);
  const fileTargetAllowed = fileTargetSupported(selectedWxid);
  const accountScope = React.useRef(new AccountScope());
  const deviceRef = React.useRef(selectedDevice);
  deviceRef.current = selectedDevice;
  accountScope.current.update(selectedDevice, selectedOwnerWxid, selectedGeneration);
  accountScope.current.selectChat(selectedWxid);
  const messageListRef = React.useRef<HTMLDivElement>(null);
  const loadedMessageChatRef = React.useRef("");
  const pendingBottomScrollChatRef = React.useRef("");

  React.useEffect(() => {
    document.documentElement.classList.toggle("dark", theme === "dark");
    localStorage.setItem(THEME_KEY, theme);
  }, [theme]);

  React.useEffect(() => {
    if (!installationNotice) return;
    const timer = window.setTimeout(() => setInstallationNotice(null), 20000);
    return () => window.clearTimeout(timer);
  }, [installationNotice]);


  React.useEffect(() => {
    setDeviceNicknameDraft(selectedModule?.device_nickname || selectedModule?.device || "");
  }, [selectedModule?.device, selectedModule?.device_nickname]);

  const visibleContacts = React.useMemo(() => {
    return contacts.filter((item) => {
      if (contactFilter === "direct") return !isRoomContact(item);
      if (contactFilter === "room") return isRoomContact(item);
      return true;
    });
  }, [contactFilter, contacts]);

  const contactByWxid = React.useMemo(() => {
    const map = new Map<string, ModuleContact>();
    contacts.forEach((item) => map.set(item.wxid, item));
    return map;
  }, [contacts]);

  const visibleRecentMessages = React.useMemo(() => {
    const conversations = new Map<string, StoredMessage>();
    recentMessages.forEach((item) => {
      const chatId = messageChatId(item);
      if (!chatId || conversations.has(chatId)) return;
      const contact = chatId ? contactByWxid.get(chatId) : undefined;
      if (contact?.deleted) return;
      const room = messageIsRoom(item, contact);
      if (contactFilter === "direct" && room) return false;
      if (contactFilter === "room" && !room) return false;
      if (query.trim()) {
        const needle = query.trim().toLowerCase();
        const haystack = [
          contact ? contactName(contact) : "",
          contact ? contactSecondary(contact) : "",
          messageContactName(item, contact),
          messagePreview(item)
        ]
          .join(" ")
          .toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      conversations.set(chatId, item);
    });
    return Array.from(conversations.values());
  }, [contactByWxid, contactFilter, query, recentMessages]);

  const selectedContact = React.useMemo(
    () => contacts.find((item) => item.wxid === selectedWxid),
    [contacts, selectedWxid]
  );

  const pendingCount = selectedModule?.pending_outbox ?? 0;
  const failedCount = selectedModule?.failed_outbox ?? 0;
  const sentCount = selectedModule?.sent_outbox ?? 0;
  const selectedModuleOffline = selectedModule?.runtime_status === "offline";
  const messageListActive = contactFilter === "messages";
  const contactQuery = messageListActive ? "" : query;
  const contactIncludeDeleted = messageListActive ? true : includeDeleted;
  const leftListCountText = messageListActive ? `${visibleRecentMessages.length} 个会话` : `${visibleContacts.length} 个对象`;
  const leftListTitle = messageListActive ? "消息列表" : "好友列表";
  const leftSearchPlaceholder = messageListActive ? "搜索消息、昵称、备注" : "搜索昵称、备注、别名";


  function applyModules(nextModules: ModuleStatus[]) {
    const active = nextModules.find((item) => item.device === deviceRef.current) ?? nextModules[0];
    accountScope.current.update(active?.device ?? "", moduleOwnerWxid(active), active?.account_generation ?? 0);
    setModules(nextModules);
    setSelectedDevice((current) => {
      if (current && nextModules.some((item) => item.device === current)) return current;
      return nextModules[0]?.device || "";
    });
  }

  const reportError = (error: unknown, fallback: string) => {
    if (error instanceof DOMException && error.name === "AbortError") return;
    setNotice(parseApiError(error, fallback).message);
  };

  // Attribute failures to the credential session that started the request.
  // A delayed 401 from an old login must never log out its replacement.
  async function authenticatedRequest<T>(session: AdminSession, request: Promise<T>): Promise<T> {
    try { return await request; }
    catch (error) {
      if (!session.isCurrent()) throw new DOMException("Superseded session", "AbortError");
      handleError(error, session);
      throw error;
    }
  }

  const refreshApiKeys = React.useCallback(async () => {
    const session = currentSession();
    if (!session) return [] as ApiKey[];
    if (!adminPassword) {
      setApiKeys([]);
      return [] as ApiKey[];
    }
    const payload = await authenticatedRequest(session, getApiKeys(session.password));
    if (!session.isCurrent()) return [] as ApiKey[];
    const nextKeys = payload.api_keys || [];
    setApiKeys(nextKeys);
    return nextKeys;
  }, [adminPassword]);

  const refreshModules = connection.refreshStatus;

  const refreshContacts = React.useCallback(
    async (device: string, ownerWxid = selectedOwnerWxid) => {
      const session = currentSession();
      if (!session) return [];
      if (!adminPassword || !device) {
        setContacts([]);
        setSelectedWxid("");
        return [] as ModuleContact[];
      }
      const ticket = accountScope.current.begin("contacts", device, ownerWxid);
      if (!ticket) return [] as ModuleContact[];
      const payload = await authenticatedRequest(session, getContacts({
        password: session.password,
        device,
        ownerWxid,
        query: contactQuery,
        includeDeleted: contactIncludeDeleted,
        limit: CONTACT_SNAPSHOT_LIMIT
      }));
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) {
        return [] as ModuleContact[];
      }
      const nextContacts = (payload.contacts || []).filter((row) => belongsToAccount(row, device, ownerWxid));
      setContacts(nextContacts);
      setSelectedWxid((current) => {
        if (current && nextContacts.some((item) => item.wxid === current)) return current;
        return nextContacts[0]?.wxid || "";
      });
      return nextContacts;
    },
    [adminPassword, contactIncludeDeleted, contactQuery, selectedOwnerWxid, selectedGeneration]
  );

  const refreshMessages = React.useCallback(
    async (device: string, wxid: string, ownerWxid = selectedOwnerWxid) => {
      const session = currentSession();
      if (!session) return [] as StoredMessage[];
      if (!adminPassword || !device || !wxid) {
        loadedMessageChatRef.current = "";
        setMessages([]);
        return [] as StoredMessage[];
      }
      const ticket = accountScope.current.begin("messages", device, ownerWxid, wxid);
      if (!ticket) return [] as StoredMessage[];
      const contact = contacts.find((item) => item.wxid === wxid);
      const payload = await authenticatedRequest(session, getMessages({
        password: session.password,
        device,
        wxid,
        ownerWxid,
        chatId: wxid,
        chatKind: chatKindForContact(wxid, contact),
        limit: 180
      }));
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) {
        return [] as StoredMessage[];
      }
      const nextMessages = (payload.messages || []).filter((row) => belongsToAccount(row, device, ownerWxid)).reverse();
      loadedMessageChatRef.current = wxid;
      setMessages(nextMessages);
      return nextMessages;
    },
    [adminPassword, contacts, selectedOwnerWxid, selectedGeneration]
  );

  const refreshRecentMessages = React.useCallback(
    async (device: string, ownerWxid = selectedOwnerWxid) => {
      const session = currentSession();
      if (!session) return [];
      if (!adminPassword || !device) {
        setRecentMessages([]);
        return [] as StoredMessage[];
      }
      const ticket = accountScope.current.begin("recent", device, ownerWxid);
      if (!ticket) return [] as StoredMessage[];
      const payload = await authenticatedRequest(session, getMessages({
        password: session.password,
        device,
        ownerWxid,
        limit: 500
      }));
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) {
        return [] as StoredMessage[];
      }
      const nextMessages = (payload.messages || []).filter((row) => belongsToAccount(row, device, ownerWxid));
      setRecentMessages(nextMessages);
      return nextMessages;
    },
    [adminPassword, selectedOwnerWxid, selectedGeneration]
  );

  const refreshOutbox = React.useCallback(async () => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword || !selectedDevice || !selectedOwnerWxid) {
      setOutboxItems([]);
      return;
    }
    const wxid = outboxChatOnly ? selectedWxid : undefined;
    if (outboxChatOnly && !wxid) { setOutboxItems([]); return; }
    const ticket = accountScope.current.begin("outbox", selectedDevice, selectedOwnerWxid, wxid);
    if (!ticket) return;
    setOutboxLoading(true);
    try {
      const payload = await authenticatedRequest(session, getOutbox({ password: session.password, device: selectedDevice,
        ownerWxid: selectedOwnerWxid, wxid, status: outboxFilter }));
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
      setOutboxItems((payload.items || []).filter((item) => belongsToAccount(item, selectedDevice, selectedOwnerWxid)
        && (!wxid || item.wxid === wxid)));
      const now = Date.parse(payload.server_time);
      setOutboxNow(Number.isFinite(now) ? now : Date.now());
      setOutboxError("");
    } catch (error) {
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
      handleError(error, session);
      setOutboxError(`发送记录读取失败：${parseApiError(error, "请稍后刷新").message}`);
    } finally {
      if (session.isCurrent() && accountScope.current.matches(ticket)) setOutboxLoading(false);
    }
  }, [adminPassword, selectedDevice, selectedOwnerWxid, selectedGeneration, selectedWxid, outboxChatOnly, outboxFilter]);

  const refreshOutboxRef = React.useRef(refreshOutbox);
  refreshOutboxRef.current = refreshOutbox;

  const changeOutboxFilter = (filter: OutboxFilter) => {
    if (filter === outboxFilter) return;
    accountScope.current.invalidate("outbox");
    setOutboxItems([]); setOutboxError(""); setOutboxFilter(filter);
  };
  const changeOutboxChatOnly = (value: boolean) => {
    accountScope.current.invalidate("outbox");
    setOutboxItems([]); setOutboxError(""); setOutboxChatOnly(value);
  };

  React.useEffect(() => {
    setOutboxItems([]); setOutboxError("");
    void refreshOutbox();
  }, [refreshOutbox, submittedOutboxId]);

  React.useEffect(() => {
    if (adminPassword) void refreshApiKeys().catch(error => reportError(error, "API Key 读取失败"));
  }, [adminPassword, refreshApiKeys]);

  React.useLayoutEffect(() => {
    loadedMessageChatRef.current = "";
    pendingBottomScrollChatRef.current = "";
    setContacts([]);
    setMessages([]);
    setRecentMessages([]);
    setSelectedWxid("");
    setDraft("");
    setSendOpen(false);
    setSending(false);
    setOutboxItems([]); setOutboxError(""); setOutboxLoading(false); setSubmittedOutboxId(undefined);
  }, [selectedScopeKey]);

  React.useEffect(() => {
    if (!selectedDevice) {
      setContacts([]);
      return;
    }
    const timer = window.setTimeout(() => {
      void refreshContacts(selectedDevice, selectedOwnerWxid).catch((error) => {
        reportError(error, "通讯录刷新失败");
      });
    }, 220);
    return () => window.clearTimeout(timer);
  }, [includeDeleted, refreshContacts, selectedDevice, selectedOwnerWxid]);

  React.useEffect(() => {
    if (visibleContacts.length === 0) {
      if (selectedWxid && !contacts.some((item) => item.wxid === selectedWxid)) {
        setSelectedWxid("");
      }
      return;
    }
    if (!selectedWxid || !visibleContacts.some((item) => item.wxid === selectedWxid)) {
      setSelectedWxid(visibleContacts[0].wxid);
    }
  }, [contacts, selectedWxid, visibleContacts]);

  React.useEffect(() => {
    if (!selectedDevice || !selectedWxid) {
      setMessages([]);
      return;
    }
    void refreshMessages(selectedDevice, selectedWxid, selectedOwnerWxid).catch((error) => {
      reportError(error, "消息刷新失败");
    });
  }, [refreshMessages, selectedDevice, selectedOwnerWxid, selectedWxid]);

  React.useLayoutEffect(() => {
    const messageList = messageListRef.current;
    if (
      !messageList ||
      !selectedWxid ||
      messages.length === 0 ||
      loadedMessageChatRef.current !== selectedWxid ||
      pendingBottomScrollChatRef.current !== selectedWxid
    ) {
      return;
    }
    messageList.scrollTop = messageList.scrollHeight;
    pendingBottomScrollChatRef.current = "";
  }, [messages, selectedWxid]);

  React.useEffect(() => {
    if (!selectedDevice) {
      setRecentMessages([]);
      return;
    }
    void refreshRecentMessages(selectedDevice, selectedOwnerWxid).catch((error) => {
      reportError(error, "消息列表刷新失败");
    });
  }, [refreshRecentMessages, selectedDevice, selectedOwnerWxid]);

  React.useEffect(() => {
    if (!autoRefresh || !adminPassword) return;
    const timer = window.setInterval(() => {
      if (document.hidden || !navigator.onLine) return;
      void refreshMessages(selectedDevice, selectedWxid, selectedOwnerWxid).catch(error => reportError(error, "同步暂时中断，正在重试"));
      void refreshRecentMessages(selectedDevice, selectedOwnerWxid).catch(error => reportError(error, "同步暂时中断，正在重试"));
      void refreshModules().catch(error => reportError(error, "同步暂时中断，正在重试"));
      void refreshOutbox();
    }, 5000);
    return () => window.clearInterval(timer);
  }, [autoRefresh, adminPassword, refreshMessages, refreshModules, refreshRecentMessages, refreshOutbox, selectedDevice, selectedOwnerWxid, selectedWxid]);


  const selectDevice = (device: string) => {
    const nextOwnerWxid = moduleOwnerWxid(modules.find((item) => item.device === device));
    const active = modules.find((item) => item.device === device);
    accountScope.current.update(device, nextOwnerWxid, active?.account_generation ?? 0);
    setSelectedDevice(device);
    setContacts([]);
    setMessages([]);
    setRecentMessages([]);
    setOutboxItems([]); setOutboxError("");
    setSelectedWxid("");
    loadedMessageChatRef.current = "";
    pendingBottomScrollChatRef.current = "";
  };

  const selectChat = (wxid: string) => {
    pendingBottomScrollChatRef.current = wxid;
    accountScope.current.selectChat(wxid);
    setOutboxItems([]); setOutboxError("");
    setSending(false);
    setDraft("");
    setSendOpen(false);
    setSelectedWxid(wxid);
    if (selectedWxid === wxid && loadedMessageChatRef.current === wxid) {
      window.requestAnimationFrame(() => {
        const messageList = messageListRef.current;
        if (messageList && pendingBottomScrollChatRef.current === wxid) {
          messageList.scrollTop = messageList.scrollHeight;
          pendingBottomScrollChatRef.current = "";
        }
      });
    }
  };

  const cancelPendingBottomScroll = () => {
    if (pendingBottomScrollChatRef.current === selectedWxid) {
      pendingBottomScrollChatRef.current = "";
    }
  };

  const selectContact = (contact: ModuleContact) => {
    selectChat(contact.wxid);
  };

  const selectRecentMessage = (message: StoredMessage) => {
    const chatId = messageChatId(message);
    if (chatId) {
      selectChat(chatId);
    }
  };

  const openSendDialog = (contact?: ModuleContact) => {
	if (selectedModuleOffline) {
	  setNotice("当前微信离线，不能创建发送任务");
	  return;
	}
    if (contact) {
      setSelectedWxid(contact.wxid);
    }
    setDraft("");
    setSendOpen(true);
  };

  const submitSend = async () => {
    const session = currentSession();
    if (!session) return;
    if (sendInFlight.current) { setNotice("上一条请求仍在提交，请等待结果后再发送"); return; }
    if (!adminPassword || !selectedDevice || !selectedWxid || !draft.trim() || selectedModuleOffline) {
      if (selectedModuleOffline) setNotice("当前微信离线，不能创建发送任务");
      return;
    }
    const ticket = accountScope.current.begin("send", selectedDevice, selectedOwnerWxid, selectedWxid);
    if (!ticket) return;
    const sentDevice = selectedDevice, sentWxid = selectedWxid, submittedDraft = draft;
    sendInFlight.current = true;
    setSending(true);
    setNotice("正在创建发送任务");
    try {
      const result = await sendText({ password: session.password, device: sentDevice, ownerWxid: selectedOwnerWxid,
        accountGeneration: selectedGeneration, wxid: sentWxid, text: submittedDraft.trim() });
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
      if (!result.ok || !result.outbox_id) throw new Error("提交结果未确认，请先查看发送记录，避免重复提交");
      setDraft((current) => current === submittedDraft ? "" : current);
      setSendOpen(false);
      accountScope.current.invalidate("outbox");
      setSubmittedOutboxId(result.outbox_id);
      setOutboxFilter("all");
      setNotice(`任务 #${result.outbox_id} 已创建，等待微信处理；结果见发送记录`);
      void refreshModules().catch(error => reportError(error, "同步暂时中断，正在重试"));
      window.setTimeout(() => {
        if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
        void refreshMessages(sentDevice, sentWxid, selectedOwnerWxid).catch(error => reportError(error, "同步暂时中断，正在重试"));
        void refreshModules().catch(error => reportError(error, "同步暂时中断，正在重试"));
      }, 800);
    } catch (error) {
      if (session.isCurrent() && accountScope.current.matches(ticket)) {
        handleError(error, session);
        const info = parseApiError(error, "提交结果未确认");
        setNotice(info.code ? info.message : `${info.message}。未确认任务是否创建，请先查看发送记录，避免重复提交。`);
        void refreshOutbox();
      }
    } finally {
      sendInFlight.current = false;
      if (session.isCurrent() && accountScope.current.matches(ticket)) setSending(false);
    }
  };

  const selectFile = (file: File | undefined) => {
    if (!file || fileDraft?.file || !fileTargetAllowed) return;
    const error = fileValidationError(file);
    setFileError(error);
    if (error) return;
    if (fileDraft) {
      if (file.name !== fileDraft.name || file.size !== fileDraft.size) {
        setFileError("请重新选择原文件，文件名和大小必须一致；服务端还会核对文件内容。");
        return;
      }
      setFileDraft({ ...fileDraft, file });
    } else setFileDraft({ file, name: file.name, size: file.size, key: newFileRequestKey(), scope: fileScope, stage: "ready", message: "" });
  };

  const discardFile = () => {
    if (!fileDraft || fileDraft.stage === "submitting") return;
    if (fileDraft.stage === "unknown" && !window.confirm("原请求结果未确认。重新选择文件会使用新请求，可能重复发送；请先查看发送记录或微信聊天。仍要放弃原请求吗？")) return;
    clearFileRequest(); setFileDraft(null); setFileError("");
  };

  const submitFile = async () => {
    const session = currentSession();
    if (!session || !fileDraft?.file || !fileScopeMatches || !fileTargetAllowed || selectedModuleOffline || sendInFlight.current) return;
    const ticket = accountScope.current.begin("send", selectedDevice, selectedOwnerWxid, selectedWxid);
    if (!ticket) return;
    const submission = fileDraft;
    const file = fileDraft.file;
    try { persistFileRequest(submission); }
    catch { setFileError("无法保存重试标识，请允许此页面使用浏览器存储后重试。"); return; }
    sendInFlight.current = true;
    setSending(true);
    setFileDraft({ ...submission, stage: "submitting", message: "正在上传文件并创建任务，请勿重复提交。" });
    const updateDraft = (stage: FileSendDraft["stage"], message: string) => setFileDraft(current =>
      current?.key === submission.key ? { ...current, stage, message } : current);
    try {
      const result = await sendFile({ password: session.password, device: submission.scope.device,
        ownerWxid: submission.scope.ownerWxid, accountGeneration: submission.scope.generation,
        wxid: submission.scope.wxid, file, idempotencyKey: submission.key });
      if (!result.ok || !result.outbox_id) throw new Error("未收到有效任务编号");
      // Settle only this draft. A late response must not close another chat's editor.
      clearFileRequest(sessionStorage, submission.key);
      setFileDraft(current => current?.key === submission.key ? null : current);
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
      setSendOpen(false);
      accountScope.current.invalidate("outbox");
      setSubmittedOutboxId(result.outbox_id); setOutboxFilter("all");
      setNotice(result.idempotent_replay
        ? `文件任务 #${result.outbox_id} 已存在，未创建新发送；请查看发送记录`
        : `文件任务 #${result.outbox_id} 已创建，等待微信处理；结果见发送记录`);
      void refreshModules().catch(error => reportError(error, "同步暂时中断，正在重试"));
    } catch (error) {
      const definiteRejection = submission.stage !== "unknown"
        && error instanceof ApiError && error.status >= 400 && error.status < 500;
      const message = definiteRejection ? parseApiError(error, "文件请求被拒绝").message
        : "提交结果未确认。文件和原请求标识已保留，可使用原请求重试；请勿另建任务。";
      if (session.isCurrent()) updateDraft(definiteRejection ? "rejected" : "unknown", message);
      if (!session.isCurrent() || !accountScope.current.matches(ticket)) return;
      handleError(error, session);
      setNotice(message);
      void refreshOutbox();
    } finally {
      sendInFlight.current = false;
      if (session.isCurrent() && accountScope.current.matches(ticket)) setSending(false);
    }
  };

  const submitApiKey = async () => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword) return;
    setSavingAdmin(true);
    try {
      const payload = await createApiKey({
        password: session.password,
        apiKey: newApiKey.trim(),
        device: newApiKeyDevice.trim(),
        nickname: newApiKeyNickname.trim()
      });
      if (!session.isCurrent()) return;
      const created = payload.api_key;
      if (!created) {
        throw new Error("服务端没有返回 API Key");
      }
      setApiKeys((current) => [created, ...current.filter((item) => item.code !== created.code)]);
      setNewApiKey("");
      setNewApiKeyNickname("");
      setNotice("API Key 已生成，可在下方列表中查看");
    } catch (error) {
      if (!session.isCurrent()) return;
      handleError(error, session);
      setNotice(error instanceof Error ? error.message : "生成 API Key 失败");
    } finally {
      if (session.isCurrent()) setSavingAdmin(false);
    }
  };

  const toggleApiKeyEnabled = async (apiKey: ApiKey) => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword || !apiKey.code) return;
    setSavingAdmin(true);
    try {
      const payload = await setApiKeyEnabled({
        password: session.password,
        apiKey: apiKey.api_key || apiKey.code,
        enabled: !apiKey.enabled
      });
      if (!session.isCurrent()) return;
      const updated = payload.api_key;
      if (!updated) {
        throw new Error("服务端没有返回 API Key");
      }
      setApiKeys((current) => [updated, ...current.filter((item) => item.code !== updated.code)]);
      await refreshModules();
      if (!session.isCurrent()) return;
      setNotice(updated.enabled ? "API Key 已启用" : "API Key 已停用");
    } catch (error) {
      if (!session.isCurrent()) return;
      handleError(error, session);
      setNotice(error instanceof Error ? error.message : "更新 API Key 状态失败");
    } finally {
      if (session.isCurrent()) setSavingAdmin(false);
    }
  };

  const revokeApiKey = async (apiKey: string) => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword || !apiKey) return;
    const ok = window.confirm(`确认删除并注销 API Key ${apiKey}？`);
    if (!ok) return;
    setSavingAdmin(true);
    try {
      await deleteApiKey({ password: session.password, apiKey });
      if (!session.isCurrent()) return;
      setApiKeys((current) => current.filter((item) => (item.api_key || item.code) !== apiKey));
      await refreshModules();
      if (!session.isCurrent()) return;
      setNotice("API Key 已删除");
    } catch (error) {
      if (!session.isCurrent()) return;
      handleError(error, session);
      setNotice(error instanceof Error ? error.message : "删除 API Key 失败");
    } finally {
      if (session.isCurrent()) setSavingAdmin(false);
    }
  };

  const submitDeviceNickname = async () => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword || !selectedDevice) return;
    setSavingAdmin(true);
    try {
      await updateDevice({
        password: session.password,
        name: selectedDevice,
        nickname: deviceNicknameDraft.trim()
      });
      if (!session.isCurrent()) return;
      await refreshModules();
      if (!session.isCurrent()) return;
      setNotice("设备显示名已保存");
    } catch (error) {
      if (!session.isCurrent()) return;
      handleError(error, session);
      setNotice(error instanceof Error ? error.message : "保存设备失败");
    } finally {
      if (session.isCurrent()) setSavingAdmin(false);
    }
  };

  const requestInstallationSwitch = async (device: string, installation: ModuleInstallation) => {
    const session = currentSession();
    if (!session) return;
    if (!adminPassword || !device || !installationIsStandby(installation) || switchingInstallation) return;
    const ok = window.confirm(`确认把设备 ${device} 切到 ${installationDisplayName(installation)}？目标手机下一次心跳时接管。`);
    if (!ok) return;
    setSwitchingInstallation({ device, id: installation.id });
    setInstallationNotice(null);
    try {
      await switchModuleInstallation({ password: session.password, device, installationId: installation.id });
      if (!session.isCurrent()) return;
      setInstallationNotice({ device, tone: "info", text: INSTALLATION_SWITCH_REQUESTED });
      setNotice("已请求切换手机");
    } catch (error) {
      if (!session.isCurrent()) return;
      handleError(error, session);
      const info = parseApiError(error, "切换手机失败");
      if (info.code === "installation_active") {
        setInstallationNotice({ device, tone: "info", text: INSTALLATION_ALREADY_ACTIVE });
      } else {
        setInstallationNotice({ device, tone: "error", text: info.message });
        setNotice(info.message);
      }
    } finally {
      if (!session.isCurrent()) return;
      setSwitchingInstallation(null);
      void refreshModules().catch(error => reportError(error, "同步暂时中断，正在重试"));
    }
  };

  if (!adminPassword) return (
    <div className="login-page"><form className="login-card" onSubmit={event => { event.preventDefault(); connection.login(password); }}>
      <span className="brand-mark"><ShieldCheck className="h-6 w-6" /></span>
      <h1 className="text-2xl font-semibold tracking-tight">欢迎回到微信看台</h1>
      <p className="mb-7 mt-2 text-sm leading-6 text-muted-foreground">一个安静、有序的微信工作台。登录后自动同步会话与设备状态。</p>
      <Label htmlFor="admin-password">管理密码</Label>
      <Input className="mb-4 mt-2" id="admin-password" type="password" autoComplete="current-password" autoFocus value={password} onChange={event => setPassword(event.target.value)} placeholder="输入管理密码" />
      {connection.error ? <p role="alert" className="mb-4 text-sm text-destructive">{parseApiError(connection.error, "连接失败").message}</p> : null}
      <Button type="submit" className="w-full" disabled={loading || !password.trim()}>{loading ? <><RefreshCw className="h-4 w-4 animate-spin" />正在连接</> : "进入看台"}</Button>
      <p className="mt-4 text-xs leading-5 text-muted-foreground">此浏览器会记住登录；公用设备使用后请退出登录。</p>
    </form></div>
  );

  return (
    <div className="observatory-app">
      <aside className="app-sidebar">
        <div className="app-brand"><span className="brand-mark"><ShieldCheck className="h-5 w-5" /></span><div><strong>微信看台</strong><span>OBSERVATORY</span></div></div>
        <nav className="app-nav" aria-label="主导航">
          {([
            ["workspace", "工作台", LayoutDashboard],
            ["devices", "设备与连接", Smartphone],
            ["access", "API 管理", KeyRound]
          ] as const).map(([id, label, Icon]) => <button key={id} type="button" aria-current={panel === id ? "page" : undefined} onClick={() => setPanel(id)}><Icon className="h-4 w-4" /><span>{label}</span>{panel === id ? <ChevronRight className="ml-auto h-3.5 w-3.5" /> : null}</button>)}
        </nav>
        <div className="sidebar-devices">
          <div className="sidebar-label">设备绑定 <span>{modules.length}</span></div>
          {modules.length ? modules.map(item => <button key={item.device} type="button" className="device-selector" aria-pressed={selectedDevice === item.device} onClick={() => selectDevice(item.device)}>
            <span className={item.runtime_status === "offline" || item.runtime_status === "disabled" ? "device-dot offline" : "device-dot"} />
            <span className="min-w-0 flex-1"><strong>{item.device_nickname || item.device}</strong><small>{item.installations?.find(phone => phone.active)?.wechat_nickname || item.device}</small></span>
          </button>) : <p className="px-3 text-xs leading-6 text-muted-foreground">手机接入后会显示在这里</p>}
        </div>
        <div className="sidebar-footer"><Button variant="ghost" size="sm" onClick={() => { setPassword(""); clearFileRequest(); connection.logout(); }}><LogOut className="h-4 w-4" />退出登录</Button><Button variant="ghost" size="icon" aria-label={theme === "dark" ? "切换到浅色主题" : "切换到深色主题"} onClick={() => setTheme(current => current === "dark" ? "light" : "dark")}>
          {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
        </Button></div>
      </aside>
      <div className="app-body">
      <header className="app-topbar">
        <div className="min-w-0"><div className="eyebrow">{panel === "workspace" ? "WORKSPACE" : panel === "devices" ? "CONNECTIONS" : "DEVELOPER"}</div><h1>{panel === "workspace" ? "消息工作台" : panel === "devices" ? "设备与连接" : "API 管理"}</h1></div>
        <div className="flex flex-wrap items-center justify-end gap-2">
          <ConnectionBadge connected={liveConnected} enabled={autoRefresh} />
          <Button variant="outline" onClick={() => void connection.refresh()} disabled={loading || !adminPassword}><RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />刷新</Button>
        </div>
      </header>
      <main className="app-main">
        <div className="workspace-notice" role="status"><span className={connection.error ? "text-destructive" : ""}>{connection.error ? parseApiError(connection.error, "连接中断").message : notice}</span><span className="hidden shrink-0 sm:inline">自动同步已{autoRefresh ? "开启" : "暂停"}</span></div>
        <section hidden={panel !== "devices"} aria-label="设备与连接" className="page-panel">
        <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Smartphone className="h-4 w-4" />
                已接入设备
              </CardTitle>
              <Badge variant="secondary">{modules.length} 个设备绑定</Badge>
            </CardHeader>
            <CardContent className="grid gap-2">
              {modules.length === 0 ? (
                <EmptyState icon={<Smartphone className="h-5 w-5" />} text="未读取到模块状态" />
              ) : (
                <>
                  {modules.map((item) => (
                    <div
                      key={item.device}
                      className={
                        item.device === selectedDevice
                          ? "rounded-lg border border-primary bg-secondary shadow-sm"
                          : "rounded-lg border bg-card transition hover:bg-secondary"
                      }
                    >
                      <button type="button" className="block w-full p-3 text-left" onClick={() => selectDevice(item.device)}>
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="truncate font-medium">{item.device || "-"}</div>
                            <div className="truncate text-xs text-muted-foreground">
                              {item.device_nickname || "未上报微信信息"}
                            </div>
                          </div>
                          <StatusBadge status={item.runtime_status} />
                        </div>
                        <div className="mt-3 grid grid-cols-2 gap-2 text-xs text-muted-foreground">
                          <span>待发：{item.pending_outbox ?? 0}</span>
                          <span>失败：{item.failed_outbox ?? 0}</span>
                        </div>
                      </button>
                      <Button className="mx-3 mb-2" variant="outline" size="xs" onClick={() => {
                        if (item.device !== selectedDevice) selectDevice(item.device);
                        setPanel("workspace");
                        window.requestAnimationFrame(() => document.getElementById("send-records")?.scrollIntoView({ block: "start", behavior: "smooth" }));
                      }}>查看发送记录</Button>
                      <InstallationList
                        compact
                        className="border-t border-border/70 px-3 pb-3 pt-2"
                        installations={item.installations}
                        switchRequest={item.switch_request}
                        switchingId={switchingInstallation?.device === item.device ? switchingInstallation.id : null}
                        disabled={!adminPassword || switchingInstallation !== null}
                        onSwitch={(installation) => void requestInstallationSwitch(item.device, installation)}
                        notice={installationNotice?.device === item.device ? installationNotice : null}
                      />
                    </div>
                  ))}
                  {modules.some((item) => (item.installations?.length ?? 0) > 0) ? <InstallationSwitchHint /> : null}
                </>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Activity className="h-4 w-4" />
                运行信号
              </CardTitle>
            </CardHeader>
            <CardContent className="grid gap-3 text-sm">
              <label className="flex items-center gap-2">
                <Checkbox checked={autoRefresh} onChange={(event) => setAutoRefresh(event.currentTarget.checked)} />
                自动同步
              </label>
              <div className="grid grid-cols-3 gap-2">
                <SignalPill label="注册" value={formatBeijingTimeAgo(selectedModule?.last_register_at)} />
                <SignalPill label="拉取" value={formatBeijingTimeAgo(selectedModule?.last_poll_at)} />
                <SignalPill label="回执" value={formatBeijingTimeAgo(selectedModule?.last_ack_at)} />
              </div>
            </CardContent>
          </Card>
        </div>
        </section>

        <section hidden={panel !== "workspace"} aria-label="工作台" className="page-panel grid min-w-0 gap-5">
          <div className="workspace-summary">
            <div className="summary-account"><span className="account-avatar"><Smartphone className="h-6 w-6" /></span><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h2 className="truncate font-semibold">{selectedModule?.installations?.find(phone => phone.active)?.wechat_nickname || selectedModule?.device_nickname || selectedDevice || "等待设备接入"}</h2><StatusBadge status={selectedModule?.runtime_status} /></div><p className="mt-1 truncate text-xs text-muted-foreground">{selectedOwnerWxid || "连接手机后即可查看会话和发送消息"}</p></div></div>
            <div className="summary-metrics"><div><span>待处理</span><strong className={pendingCount ? "text-amber-600" : ""}>{pendingCount}</strong></div><div><span>已发送</span><strong>{sentCount}</strong></div><div><span>通讯录</span><strong>{contacts.length}</strong></div></div>
          </div>

          <div className="conversation-grid">
            <Card className="min-w-0 contact-panel">
              <CardHeader className="items-start">
                <div className="grid gap-1">
                  <CardTitle className="flex items-center gap-2">
                    {messageListActive ? <MessageCircle className="h-4 w-4" /> : <Users className="h-4 w-4" />}
                    {leftListTitle}
                  </CardTitle>
                  <div className="text-xs text-muted-foreground">
                    {selectedDevice || "未选择设备"} · {leftListCountText}
                  </div>
                </div>
                <Badge variant={includeDeleted ? "warning" : "secondary"}>{includeDeleted ? "含删除" : "有效"}</Badge>
              </CardHeader>
              <CardContent className="grid gap-3">
                <div className="relative">
                  <Search className="pointer-events-none absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
                  <Input
                    className="pl-8"
                    value={query}
                    onChange={(event: React.ChangeEvent<HTMLInputElement>) => setQuery(event.target.value)}
                    placeholder={leftSearchPlaceholder}
                  />
                </div>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <Tabs className="gap-0">
                    <TabsList>
                      <TabsTrigger active={contactFilter === "all"} onClick={() => setContactFilter("all")}>
                        全部
                      </TabsTrigger>
                      <TabsTrigger active={contactFilter === "direct"} onClick={() => setContactFilter("direct")}>
                        好友
                      </TabsTrigger>
                      <TabsTrigger active={contactFilter === "room"} onClick={() => setContactFilter("room")}>
                        群聊
                      </TabsTrigger>
                      <TabsTrigger active={contactFilter === "messages"} onClick={() => setContactFilter("messages")}>
                        消息列表
                      </TabsTrigger>
                    </TabsList>
                  </Tabs>
                  <label className="flex h-9 items-center gap-2 rounded-md border px-3 text-sm">
                    <Checkbox
                      checked={includeDeleted}
                      onChange={(event: React.ChangeEvent<HTMLInputElement>) => setIncludeDeleted(event.currentTarget.checked)}
                    />
                    含删除
                  </label>
                </div>

                {messageListActive ? (
                  <div className="contact-list">
                    {visibleRecentMessages.length === 0 ? (
                      <EmptyState
                        icon={<MessageCircle className="h-5 w-5" />}
                        text={selectedDevice ? "当前筛选暂无消息" : "请先连接并选择模块"}
                      />
                    ) : (
                      visibleRecentMessages.slice(0, 80).map((item) => {
                        const chatId = messageChatId(item);
                        const contact = chatId ? contactByWxid.get(chatId) : undefined;
                        return (
                          <button
                            key={`recent:${chatId}`}
                            type="button"
                            className={
                              chatId === selectedWxid
                                ? "contact-row selected"
                                : "contact-row"
                            }
                            onClick={() => selectRecentMessage(item)}
                          >
                            <div className="flex min-w-0 items-center justify-between gap-2">
                              <div className="min-w-0 truncate text-sm font-medium">{messageContactName(item, contact)}</div>
                              <span className="shrink-0 text-[11px] text-muted-foreground">{formatBeijingTimeAgo(item.created_at)}</span>
                            </div>
                            <div className="mt-1 truncate text-xs text-muted-foreground">
                              {messagePreview(item, contactByWxid, selectedModule)}
                            </div>
                          </button>
                        );
                      })
                    )}
                  </div>
                ) : (
                <div className="contact-list">
                  {visibleContacts.length === 0 ? (
                    <EmptyState
                      icon={<Users className="h-5 w-5" />}
                      text={selectedDevice ? "当前筛选没有好友" : "请先连接并选择模块"}
                    />
                  ) : (
                    visibleContacts.map((item) => (
                      <button
                        key={`${item.device}:${item.owner_wxid}:${item.wxid}`}
                        className={
                          item.wxid === selectedWxid
                            ? "contact-row selected"
                            : "contact-row"
                        }
                        onClick={() => selectContact(item)}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <div className="flex min-w-0 items-center gap-2">
                              {isRoomContact(item) ? (
                                <Hash className="h-4 w-4 shrink-0 text-muted-foreground" />
                              ) : (
                                <UserRound className="h-4 w-4 shrink-0 text-muted-foreground" />
                              )}
                              <span className="truncate font-medium">{contactName(item)}</span>
                            </div>
                            <div className="mt-1 truncate text-xs text-muted-foreground">{contactSecondary(item)}</div>
                          </div>
                          <Badge variant={item.deleted ? "destructive" : isRoomContact(item) ? "warning" : "success"}>
                            {item.deleted ? "已删除" : isRoomContact(item) ? "群聊" : "好友"}
                          </Badge>
                        </div>
                      </button>
                    ))
                  )}
                </div>
                )}
              </CardContent>
            </Card>

            <Card className="min-w-0 chat-panel">
              <CardHeader className="items-start md:items-center">
                <div className="min-w-0">
                  <CardTitle className="flex items-center gap-2">
                    <MessageCircle className="h-4 w-4" />
                    消息列表
                  </CardTitle>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                    <span className="max-w-[420px] truncate">{selectedChatTitle(selectedWxid, selectedContact)}</span>
                    {selectedContact ? <Badge variant="outline">{isRoomContact(selectedContact) ? "群聊" : "好友"}</Badge> : null}
                    {selectedContact?.deleted ? <Badge variant="destructive">已删除</Badge> : null}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    variant="outline"
                    onClick={() => void refreshMessages(selectedDevice, selectedWxid, selectedOwnerWxid).catch(error => reportError(error, "消息读取失败"))}
                    disabled={!selectedDevice || !selectedWxid || !adminPassword}
                  >
                    <RefreshCw className="h-4 w-4" />
                    刷新消息
                  </Button>
                  <Button onClick={() => openSendDialog()} disabled={!selectedDevice || !selectedWxid || !adminPassword || selectedModuleOffline}>
                    <Send className="h-4 w-4" />
                    发消息
                  </Button>
                </div>
              </CardHeader>
              <CardContent className="grid gap-3">
                <div
                  ref={messageListRef}
                  onPointerDown={cancelPendingBottomScroll}
                  onScroll={cancelPendingBottomScroll}
                  onTouchStart={cancelPendingBottomScroll}
                  onWheel={cancelPendingBottomScroll}
                  className="message-timeline"
                >
                  {!selectedWxid ? (
                    <EmptyState icon={<MessageCircle className="h-5 w-5" />} text="选择一个会话，开始交流" />
                  ) : messages.length === 0 ? (
                    <EmptyState icon={<MessageCircle className="h-5 w-5" />} text="这里还没有消息，发送第一句话吧" />
                  ) : (
                    messages.map((item) => (
                      <MessageBubble
                        key={`${item.id}:${item.created_at}`}
                        message={item}
                        adminPassword={adminPassword}
                        contactByWxid={contactByWxid}
                        module={selectedModule}
                      />
                    ))
                  )}
                </div>

                <div className="message-composer">
                  <Label htmlFor="inline-send">发送内容</Label>
                  <Textarea
                    id="inline-send"
                    value={draft}
                    onChange={(event) => setDraft(event.target.value)}
                    placeholder="输入要发送到微信的文本"
                    disabled={!selectedWxid || sending || selectedModuleOffline}
                  />
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-xs text-muted-foreground">
                      {selectedModuleOffline ? "当前微信离线，请查看发送记录确认任务状态" : pendingCount > 0 ? `待发送 ${pendingCount} 条` : failedCount > 0 ? `失败 ${failedCount} 条` : "发送队列空闲"}
                    </span>
                    <Button onClick={() => void submitSend()} disabled={sending || selectedModuleOffline || !adminPassword || !selectedDevice || !selectedWxid || !draft.trim()}>
                      <Send className="h-4 w-4" />
                      {sending ? "提交中" : "加入队列"}
                    </Button>
                  </div>
                </div>
                <OutboxList items={outboxItems} filter={outboxFilter} onFilter={changeOutboxFilter}
                  chatOnly={outboxChatOnly} onChatOnly={changeOutboxChatOnly} hasChat={!!selectedWxid}
                  now={outboxNow} error={outboxError} loading={outboxLoading} offline={selectedModuleOffline}
                  enabled={!!adminPassword && !!selectedDevice && !!selectedOwnerWxid}
                  onRefresh={() => void refreshOutbox()} contacts={contactByWxid} submittedId={submittedOutboxId} />
              </CardContent>
            </Card>
          </div>

        </section>
        <section hidden={panel !== "access"} aria-label="API 管理" className="page-panel">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <KeyRound className="h-4 w-4" />
                API Key 与设备管理
              </CardTitle>
              <Button variant="outline" onClick={() => void refreshApiKeys().catch(error => reportError(error, "API Key 读取失败"))} disabled={loading || !adminPassword}>
                <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
                刷新 API Key
              </Button>
            </CardHeader>
            <CardContent className="grid gap-5 lg:grid-cols-2">
              <div className="grid content-start gap-3 rounded-lg border bg-card p-3">
                <div className="text-sm font-medium">生成 API Key</div>
                <div className="grid gap-2">
                  <Label htmlFor="new-api-key">指定 Key</Label>
                  <Input
                    id="new-api-key"
                    value={newApiKey}
                    onChange={(event) => setNewApiKey(event.target.value)}
                    placeholder="留空则服务端自动生成"
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="new-api-key-device">设备名</Label>
                  <Input
                    id="new-api-key-device"
                    value={newApiKeyDevice}
                    onChange={(event) => setNewApiKeyDevice(event.target.value)}
                    placeholder="留空则服务端自动生成"
                  />
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="new-api-key-nickname">显示名</Label>
                  <Input
                    id="new-api-key-nickname"
                    value={newApiKeyNickname}
                    onChange={(event) => setNewApiKeyNickname(event.target.value)}
                    placeholder="Web 端显示名称"
                  />
                </div>
                <Button onClick={() => void submitApiKey()} disabled={savingAdmin || !adminPassword}>
                  <KeyRound className="h-4 w-4" />
                  {savingAdmin ? "生成中" : "生成 API Key"}
                </Button>
              </div>

              <div className="grid content-start gap-3 rounded-lg border bg-card p-3">
                <div className="text-sm font-medium">当前设备</div>
                <div className="grid gap-1 text-sm">
                  <span className="text-muted-foreground">设备名</span>
                  <span className="font-medium">{selectedDevice || "-"}</span>
                </div>
                <div className="grid gap-1 text-sm">
                  <span className="text-muted-foreground">当前 wxid</span>
                  <span className="break-all font-mono text-xs">{selectedOwnerWxid || "-"}</span>
                </div>
                <div className="grid gap-2">
                  <Label htmlFor="device-nickname">Web 显示名</Label>
                  <Input
                    id="device-nickname"
                    value={deviceNicknameDraft}
                    onChange={(event) => setDeviceNicknameDraft(event.target.value)}
                    placeholder="只在 Web 端设置"
                    disabled={!selectedDevice}
                  />
                </div>
                <Button onClick={() => void submitDeviceNickname()} disabled={savingAdmin || !adminPassword || !selectedDevice}>
                  <Smartphone className="h-4 w-4" />
                  保存设备显示名
                </Button>
              </div>

              <div className="overflow-auto rounded-lg border lg:col-span-2">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="min-w-[220px]">API Key</TableHead>
                      <TableHead className="w-[96px]">状态</TableHead>
                      <TableHead className="w-[128px]">操作</TableHead>
                      <TableHead className="min-w-[150px]">设备名</TableHead>
                      <TableHead className="min-w-[160px]">显示名</TableHead>
                      <TableHead className="min-w-[180px]">更新时间</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {apiKeys.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={6}>
                          <EmptyState icon={<KeyRound className="h-5 w-5" />} text="还没有 API Key，生成一个即可接入手机" />
                        </TableCell>
                      </TableRow>
                    ) : (
                      apiKeys.slice(0, 12).map((item) => (
                        <TableRow key={item.code}>
                          <TableCell className="font-mono text-xs">{item.api_key || item.code}</TableCell>
                          <TableCell className="text-xs">
                            <Badge variant={item.enabled === false ? "secondary" : "success"}>
                              {item.enabled === false ? "停用" : "启用"}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-1">
                              <Button
                                variant="ghost"
                                size="icon"
                                title={item.enabled === false ? "启用 API Key" : "停用 API Key"}
                                onClick={() => void toggleApiKeyEnabled(item)}
                                disabled={savingAdmin || !adminPassword}
                              >
                                {item.enabled === false ? <PlayCircle className="h-4 w-4" /> : <PauseCircle className="h-4 w-4" />}
                              </Button>
                              <Button
                                variant="ghost"
                                size="icon"
                                title="删除 API Key"
                                onClick={() => void revokeApiKey(item.api_key || item.code)}
                                disabled={savingAdmin || !adminPassword}
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </div>
                          </TableCell>
                          <TableCell className="text-xs">{item.device || "自动生成"}</TableCell>
                          <TableCell className="text-xs">{item.nickname || "-"}</TableCell>
                          <TableCell className="text-xs">{formatBeijingTimeAgo(item.updated_at || item.created_at)}</TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
        </section>
        <section hidden={panel !== "devices"} className="page-panel">
          <details className="rounded-lg border bg-card">
            <summary className="cursor-pointer px-4 py-3 text-sm font-medium">模块运行明细</summary>
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Activity className="h-4 w-4" />
                模块运行明细
              </CardTitle>
              <Button variant="outline" onClick={() => void refreshModules()} disabled={loading || !adminPassword}>
                <RefreshCw className={loading ? "h-4 w-4 animate-spin" : "h-4 w-4"} />
                刷新
              </Button>
            </CardHeader>
            <CardContent>
              <div className="overflow-auto rounded-md border">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead className="min-w-[160px]">设备</TableHead>
                      <TableHead className="min-w-[120px]">状态</TableHead>
                      <TableHead className="min-w-[140px]">队列</TableHead>
                      <TableHead className="min-w-[220px]">最近活动</TableHead>
                      <TableHead className="min-w-[280px]">手机</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {modules.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={5}>
                          <EmptyState icon={<Smartphone className="h-5 w-5" />} text="未读取到模块状态" />
                        </TableCell>
                      </TableRow>
                    ) : (
                      modules.map((item) => (
                        <TableRow key={item.device} className={item.device === selectedDevice ? "bg-secondary/70" : undefined}>
                          <TableCell>
                            <div className="font-medium">{item.device}</div>
                            <div className="truncate text-xs text-muted-foreground">{item.device_nickname || "未上报微信信息"}</div>
                          </TableCell>
                          <TableCell>
                            <StatusBadge status={item.runtime_status} />
                          </TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1 text-xs">
                              <Badge variant="warning">待 {item.pending_outbox ?? 0}</Badge>
                              <Badge variant="secondary">中 {item.leased_outbox ?? 0}</Badge>
                              <Badge variant={item.failed_outbox ? "destructive" : "success"}>败 {item.failed_outbox ?? 0}</Badge>
                            </div>
                          </TableCell>
                          <TableCell className="text-xs">
                            <div>拉取：{formatBeijingDateTime(item.last_poll_at)}</div>
                            <div className="text-muted-foreground">回执：{formatBeijingDateTime(item.last_ack_at || item.last_outbound_ack_at)}</div>
                          </TableCell>
                          <TableCell className="text-xs">
                            {item.installations?.length ? (
                              <InstallationList compact installations={item.installations} switchRequest={item.switch_request} />
                            ) : (
                              <span className="text-muted-foreground">-</span>
                            )}
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>
            </CardContent>
          </Card>
          </details>
        </section>
      </main>
      </div>

      <Dialog open={sendOpen} onOpenChange={setSendOpen}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto" onClose={() => setSendOpen(false)}>
          <DialogHeader>
            <DialogTitle>发送微信消息</DialogTitle>
            <DialogDescription>{selectedChatTitle(selectedWxid, selectedContact)}</DialogDescription>
          </DialogHeader>
          <div className="grid gap-3">
            <div className="grid gap-2">
              <Label htmlFor="send-target">发送对象</Label>
              <Input id="send-target" value={selectedChatTitle(selectedWxid, selectedContact)} readOnly />
            </div>
            <TabsList aria-label="发送类型">
              <TabsTrigger active={sendMode === "text"} onClick={() => setSendMode("text")}>文本</TabsTrigger>
              <TabsTrigger active={sendMode === "file"} onClick={() => setSendMode("file")}>文件</TabsTrigger>
            </TabsList>
            {sendMode === "text" ? <div className="grid gap-2">
              <Label htmlFor="send-text">消息内容</Label>
              <Textarea
                id="send-text"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
                placeholder="输入要发送到微信的文本"
                autoFocus
                disabled={selectedModuleOffline || sending}
              />
            </div> : <div className="grid min-w-0 gap-2">
              {!fileTargetAllowed ? <p role="alert" className="text-xs leading-5">文件发送目前仅支持群聊和文件传输助手，请先选择相应会话。</p> : null}
              {!fileDraft?.file && fileTargetAllowed ? <>
                <Label htmlFor="send-file">选择文件</Label>
                <Input id="send-file" type="file" disabled={sending || selectedModuleOffline}
                  onChange={event => { selectFile(event.currentTarget.files?.[0]); event.currentTarget.value = ""; }} />
              </> : null}
              {fileDraft ? <div className="grid min-w-0 gap-2 rounded-md border p-3">
                <p className="break-all text-sm" data-testid="file-draft-name">{fileDraft.name}</p>
                <p className="text-xs text-muted-foreground">{formatFileSize(fileDraft.size)}</p>
                {fileDraft.message ? <p role={fileDraft.stage === "submitting" ? "status" : "alert"} className="text-xs leading-5">{fileDraft.message}</p> : null}
                {!fileScopeMatches ? <p role="alert" className="text-xs text-destructive">该文件请求属于原账号和会话，不能发送到当前会话。请切回原会话核查发送记录。</p> : null}
                <Button type="button" size="sm" variant="outline" disabled={fileDraft.stage === "submitting"} onClick={discardFile}>取消选择</Button>
              </div> : null}
              {fileError ? <p role="alert" className="text-xs text-destructive">{fileError}</p> : null}
              <p className="text-xs leading-5 text-muted-foreground">单个普通文件，最多 25 MiB；暂不支持图片和视频。提交后在发送记录查看结果。</p>
              <p className="text-xs leading-5 text-muted-foreground">临时传输文件会自动清理；微信聊天中的原生附件会保留，请在微信内管理。</p>
            </div>}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => setSendOpen(false)}>
              {sendMode === "file" ? "收起" : "取消"}
            </Button>
            <Button type="button" onClick={() => void (sendMode === "file" ? submitFile() : submitSend())}
              disabled={sending || selectedModuleOffline || (sendMode === "file" ? !fileTargetAllowed || !fileDraft?.file || !fileScopeMatches || fileDraft.stage === "submitting" : !draft.trim())}>
              <Send className="h-4 w-4" />
              {sending ? "提交中" : sendMode === "file" && fileDraft && fileDraft.stage !== "ready" ? "使用原请求重试" : "加入队列"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function MessageBubble({
  message,
  adminPassword,
  contactByWxid,
  module
}: {
  message: StoredMessage;
  adminPassword: string;
  contactByWxid: Map<string, ModuleContact>;
  module?: ModuleStatus;
}) {
  const outgoing = message.direction === "sent";
  const hasAttachment = Boolean(message.media_url || message.media_kind || mediaKindFromType(message.message_type));
  const senderName = messageSenderName(message, contactByWxid, module);
  return (
    <div className={outgoing ? "flex justify-end" : "flex justify-start"}>
      <div
        className={
          outgoing
            ? "max-w-[82%] rounded-lg border border-primary/20 bg-primary px-3 py-2 text-primary-foreground shadow-sm"
            : "max-w-[82%] rounded-lg border bg-secondary px-3 py-2 text-secondary-foreground shadow-sm"
        }
      >
        <div className="mb-1 flex flex-wrap items-center gap-2 text-xs opacity-80">
          <span>{outgoing ? "发出" : "收到"}</span>
          <span className="font-medium">{senderName}</span>
          <span>{formatBeijingDateTime(message.created_at)}</span>
        </div>
        {message.text ? <div className="whitespace-pre-wrap break-words text-sm leading-6">{message.text}</div> : null}
        {hasAttachment ? <MessageAttachment message={message} adminPassword={adminPassword} outgoing={outgoing} /> : null}
      </div>
    </div>
  );
}

function MessageAttachment({
  message,
  adminPassword,
  outgoing
}: {
  message: StoredMessage;
  adminPassword: string;
  outgoing: boolean;
}) {
  const kind = message.media_kind || mediaKindFromType(message.message_type) || "file";
  const url = message.media_url ? mediaURL(message.media_url, adminPassword) : "";
  const title = message.media_name || messageTypeText(message.message_type);
  const shell = outgoing ? "border-primary-foreground/30 bg-primary-foreground/10" : "border-border bg-background/80";

  if (!url) {
    return (
      <div className={`mt-2 rounded-md border border-dashed p-3 text-xs ${shell}`}>
        <div className="flex items-center gap-2 font-medium">
          {mediaIcon(kind)}
          <span>{title}</span>
        </div>
        <div className="mt-1 opacity-80">模块已收到此类消息，但附件文件还没有上传。</div>
      </div>
    );
  }

  if (kind === "image") {
    return (
      <a href={url} target="_blank" rel="noreferrer" className={`mt-2 block overflow-hidden rounded-md border ${shell}`}>
        <img src={url} alt={title} className="max-h-72 w-full object-contain" />
      </a>
    );
  }

  if (kind === "voice") {
    return (
      <div className={`mt-2 grid gap-2 rounded-md border p-3 ${shell}`}>
        <div className="flex items-center gap-2 text-sm font-medium">
          <Mic className="h-4 w-4" />
          <span>{title}</span>
        </div>
        <audio controls preload="none" src={url} className="h-9 w-full" />
        <MediaDownload url={url} name={title} size={message.media_size} />
      </div>
    );
  }

  if (kind === "video") {
    return (
      <div className={`mt-2 overflow-hidden rounded-md border ${shell}`}>
        <video controls preload="metadata" src={url} className="max-h-80 w-full bg-black" />
      </div>
    );
  }

  return (
    <div className={`mt-2 grid gap-2 rounded-md border p-3 text-sm ${shell}`}>
      <div className="flex items-center gap-2 font-medium">
        {mediaIcon(kind)}
        <span className="truncate">{title}</span>
      </div>
      <MediaDownload url={url} name={title} size={message.media_size} />
    </div>
  );
}

function MediaDownload({ url, name, size }: { url: string; name: string; size?: number }) {
  return (
    <a
      href={url}
      target="_blank"
      rel="noreferrer"
      download={name}
      className="inline-flex h-8 w-fit items-center gap-2 rounded-md border bg-background px-3 text-xs text-foreground shadow-sm hover:bg-secondary"
    >
      <Download className="h-3.5 w-3.5" />
      下载{size ? ` · ${formatBytes(size)}` : ""}
    </a>
  );
}

function ConnectionBadge({ connected, enabled }: { connected: boolean; enabled: boolean }) {
  if (!enabled) {
    return (
      <Badge variant="secondary">
        <WifiOff className="mr-1 h-3.5 w-3.5" />
        网页实时关闭
      </Badge>
    );
  }
  return (
    <Badge variant={connected ? "success" : "warning"}>
      {connected ? <Wifi className="mr-1 h-3.5 w-3.5" /> : <WifiOff className="mr-1 h-3.5 w-3.5" />}
      {connected ? "网页已连接" : "网页连接中"}
    </Badge>
  );
}

function StatusBadge({ status }: { status?: string }) {
  if (status === "ready") {
    return <Badge variant="success">就绪</Badge>;
  }
  if (status === "pending" || status === "sending") {
    return <Badge variant="warning">{statusText(status)}</Badge>;
  }
  if (status === "failed" || status === "unregistered") {
    return <Badge variant="destructive">{statusText(status)}</Badge>;
  }
  if (status === "offline") {
	return <Badge variant="destructive"><WifiOff className="mr-1 h-3.5 w-3.5" />离线</Badge>;
  }
  if (status === "disabled") {
    return <Badge variant="secondary">{statusText(status)}</Badge>;
  }
  return <Badge variant="secondary">{statusText(status)}</Badge>;
}

function SignalPill({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border bg-background px-2 py-2">
      <div className="text-[11px] text-muted-foreground">{label}</div>
      <div className="mt-1 truncate text-xs font-medium">{value}</div>
    </div>
  );
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="grid min-h-[120px] place-items-center rounded-lg border border-dashed bg-background/60 p-6 text-center text-sm text-muted-foreground">
      <div className="grid justify-items-center gap-2">
        <div className="text-muted-foreground">{icon}</div>
        <div>{text}</div>
      </div>
    </div>
  );
}

function contactName(contact: ModuleContact) {
  if (isFileHelperContact(contact)) {
    return contact.remark || contact.nickname || "文件传输助手";
  }
  const name = contact.remark || contact.nickname || contact.alias;
  if (name) {
    return name;
  }
  return isRoomContact(contact) ? "未命名群聊" : "未命名好友";
}

function contactSecondary(contact: ModuleContact) {
  const nickname = contact.nickname && contact.nickname !== contactName(contact) ? contact.nickname : "";
  const parts = [nickname, contact.alias].filter(Boolean);
  return parts.join(" · ") || "-";
}

function contactKindText(contact: ModuleContact) {
  return isRoomContact(contact) ? "群聊" : "好友";
}

function selectedChatTitle(wxid: string, contact?: ModuleContact) {
  if (contact) {
    return contactName(contact);
  }
  if (!wxid) {
    return "未选择好友";
  }
  return wxid.toLowerCase().endsWith("@chatroom") ? "未收录群聊" : "未收录好友";
}

function messageChatId(message: StoredMessage) {
  if (message.chat_id) {
    return message.chat_id;
  }
  if (message.room_id) {
    return message.room_id;
  }
  if (message.direction === "sent") {
    return message.to_wxid || message.from_wxid || message.sender_wxid || "";
  }
  return message.from_wxid || message.sender_wxid || message.to_wxid || "";
}

function messageIsRoom(message: StoredMessage, contact?: ModuleContact) {
  const chatId = messageChatId(message).toLowerCase();
  return Boolean(contact?.chatroom || message.chat_kind === "room" || message.room_id || chatId.endsWith("@chatroom"));
}

function messageContactName(message: StoredMessage, contact?: ModuleContact) {
  if (contact) {
    return contactName(contact);
  }
  return messageIsRoom(message) ? "未收录群聊" : "未收录好友";
}

function messageSenderName(message: StoredMessage, contactByWxid: Map<string, ModuleContact>, module?: ModuleStatus) {
  const outgoing = message.direction === "sent";
  if (outgoing) {
    return moduleSelfName(module);
  }

  const senderWxid = message.sender_wxid || "";
  if (messageIsRoom(message) && !senderWxid) {
    return "群聊系统消息";
  }

  const wxid = senderWxid || message.from_wxid || "";
  const contact = wxid ? contactByWxid.get(wxid) : undefined;
  if (contact) {
    return contactName(contact);
  }

  if (messageIsRoom(message)) {
    return senderWxid ? "群成员" : "群聊";
  }
  return wxid && wxid === moduleOwnerWxid(module) ? moduleSelfName(module) : "对方";
}

function moduleSelfName(module?: ModuleStatus) {
  return module?.device_nickname || "我";
}

function messagePreview(message: StoredMessage, contactByWxid?: Map<string, ModuleContact>, module?: ModuleStatus) {
  const direction = message.direction === "sent" ? "发出" : "收到";
  const body = message.text?.trim() || message.media_name || messageTypeText(message.message_type);
  const sender = contactByWxid ? messageSenderName(message, contactByWxid, module) : "";
  return [direction, sender, body || "空消息"].filter(Boolean).join(" · ");
}

function isRoomContact(contact: ModuleContact) {
  return Boolean(contact.chatroom || contact.wxid.toLowerCase().endsWith("@chatroom"));
}

function isFileHelperContact(contact: ModuleContact) {
  return contact.wxid.toLowerCase() === "filehelper";
}

function statusText(status?: string) {
  switch (status) {
    case "ready":
      return "就绪";
    case "pending":
      return "待发送";
    case "sending":
      return "发送中";
    case "failed":
      return "失败";
	case "offline":
	  return "离线";
    case "disabled":
      return "已停用";
    case "unregistered":
      return "未注册";
    default:
      return status || "-";
  }
}

function messageTypeText(type: number) {
  switch (type) {
    case 1:
      return "文本";
    case 3:
      return "图片";
    case 34:
      return "语音";
    case 43:
      return "视频";
    case 47:
      return "表情";
    case 48:
      return "位置";
    case 49:
      return "链接/文件";
    case 62:
      return "小视频";
    case 10000:
      return "系统消息";
    default:
      return `类型 ${type}`;
  }
}

function mediaKindFromType(type?: number) {
  switch (type) {
    case 3:
      return "image";
    case 34:
      return "voice";
    case 43:
    case 62:
      return "video";
    case 47:
      return "emoji";
    case 48:
      return "location";
    case 49:
      return "file";
    default:
      return "";
  }
}

function mediaURL(path: string, password: string) {
  const url = new URL(path.startsWith("/") ? publicPath(path) : path, window.location.origin);
  if (password) {
    url.searchParams.set("password", password);
  }
  return `${url.pathname}${url.search}`;
}

function mediaIcon(kind: string) {
  switch (kind) {
    case "image":
      return <ImageIcon className="h-4 w-4" />;
    case "voice":
      return <Mic className="h-4 w-4" />;
    case "video":
      return <Video className="h-4 w-4" />;
    case "file":
      return <Paperclip className="h-4 w-4" />;
    default:
      return <FileText className="h-4 w-4" />;
  }
}

function formatBytes(value?: number) {
  if (!value || value <= 0) {
    return "";
  }
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}

function liveEventTouchesChat(event: LiveMessageEvent, chatId: string) {
  return [event.chat_id, event.from, event.to, event.room_id, event.sender].some((value) => value === chatId);
}

function moduleOwnerWxid(module?: ModuleStatus) {
  return module?.device_wxid || "";
}

function chatKindForContact(wxid: string, contact?: ModuleContact) {
  if (contact?.chatroom || wxid.toLowerCase().endsWith("@chatroom")) {
    return "room";
  }
  return "direct";
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
