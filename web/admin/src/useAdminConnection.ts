import { useEffect, useRef, useState } from "react";
import { ApiError, getModules, openLiveEvents, parseLiveMessageEvent } from "./api";
import type { LiveMessageEvent, ModuleStatus } from "./types";

const PASSWORD_KEY = "wgc_admin_password";

/** Check isCurrent immediately before publishing any asynchronous request result. */
export type AdminSession = {
  password: string;
  signal: AbortSignal;
  isCurrent: () => boolean;
};

type Options = {
  autoRefresh: boolean;
  onAuthenticated: (modules: ModuleStatus[], session: AdminSession) => void;
  onRefresh: (session: AdminSession) => Promise<void>;
  onMessage: (message: LiveMessageEvent, session: AdminSession) => void | Promise<void>;
  onReset: () => void;
};

type State = { password: string; connecting: boolean; liveConnected: boolean; error: string };
const initialState: State = { password: "", connecting: false, liveConnected: false, error: "" };
type Controls = {
  login: (password: string) => void;
  logout: () => void;
  refresh: () => Promise<void>;
  refreshStatus: () => Promise<void>;
  currentSession: () => AdminSession | null;
  handleError: (error: unknown, session: AdminSession) => void;
  setLiveEnabled: (enabled: boolean) => void;
};

/** One credential session and one EventSource, independent of the selected device/chat. */
export function useAdminConnection(options: Options) {
  const latest = useRef(options);
  latest.current = options;
  const controls = useRef<Controls | null>(null);
  const [state, setState] = useState<State>(initialState);

  useEffect(() => {
    let disposed = false;
    let session: AdminSession | null = null;
    let abort: AbortController | undefined;
    let authenticated = false;
    let source: EventSource | undefined;
    let retryTimer: number | undefined;
    let attempts = 0;
    let lastRefreshAt = 0;
    let enabled = latest.current.autoRefresh;
    let running: { session: AdminSession; promise: Promise<void>; full: boolean } | undefined;

    const emit = (patch: Partial<State>) => { if (!disposed) setState((old) => ({ ...old, ...patch })); };
    const clearRetry = () => { if (retryTimer !== undefined) window.clearTimeout(retryTimer); retryTimer = undefined; };
    const closeSource = () => { source?.close(); source = undefined; emit({ liveConnected: false }); };
    const storePassword = (password: string) => {
      try {
        if (password) localStorage.setItem(PASSWORD_KEY, password);
        else localStorage.removeItem(PASSWORD_KEY);
      } catch { /* A blocked browser storage area must not prevent this login. */ }
    };
    const end = (error = "") => {
      abort?.abort(); session = null; authenticated = false; running = undefined;
      clearRetry(); closeSource(); attempts = 0; lastRefreshAt = 0;
      emit({ ...initialState, error });
      latest.current.onReset();
    };
    const handleError = (error: unknown, owner: AdminSession) => {
      if (!owner.isCurrent()) return;
      if (error instanceof ApiError && error.status === 401) {
        end("管理密码无效或已失效，请重新登录");
        storePassword("");
      }
    };
    const scheduleRetry = () => {
      if (!session || disposed || !enabled || !navigator.onLine || retryTimer !== undefined) return;
      const delay = Math.min(15000, 1000 * 2 ** Math.min(attempts++, 4));
      retryTimer = window.setTimeout(() => { retryTimer = undefined; void refresh(); }, delay);
    };
    const openSource = (owner: AdminSession) => {
      if (!owner.isCurrent() || !authenticated || !enabled || source || !navigator.onLine) return;
      const opened = openLiveEvents(owner.password);
      source = opened;
      const current = () => owner.isCurrent() && source === opened;
      opened.addEventListener("ready", () => {
        if (!current()) return;
        attempts = 0;
        emit({ liveConnected: true, error: "" });
      });
      opened.addEventListener("message", (event) => {
        if (!current()) return;
        let message: LiveMessageEvent;
        try { message = parseLiveMessageEvent((event as MessageEvent).data); }
        catch { return; }
        Promise.resolve().then(() => {
          if (current()) return latest.current.onMessage(message, owner);
        }).catch((error) => handleError(error, owner));
      });
      opened.onerror = () => {
        if (!current()) return;
        // EventSource hides HTTP status. Close its automatic retry, then authenticate
        // via an ordinary request so a revoked password cannot retry forever.
        closeSource();
        scheduleRetry();
      };
    };
    const refresh = (full = true): Promise<void> => {
      const owner = session;
      if (!owner || disposed) return Promise.resolve();
      if (running?.session === owner) {
        // A manual/recovery refresh joining a status poll also needs the child data.
        if (full && !running.full) emit({ connecting: true });
        running.full ||= full;
        return running.promise;
      }
      clearRetry();
      if (full || !authenticated) emit({ connecting: true });
      const wasAuthenticated = authenticated;
      const promise = (async () => {
        try {
          const result = await getModules(owner.password, owner.signal);
          if (!owner.isCurrent()) return;
          authenticated = true;
          storePassword(owner.password);
          emit({ password: owner.password, error: "" });
          latest.current.onAuthenticated(result.modules || [], owner);
          if (wasAuthenticated && running?.full) await latest.current.onRefresh(owner);
          if (!owner.isCurrent()) return;
          lastRefreshAt = Date.now();
          openSource(owner);
        } catch (error) {
          if (!owner.isCurrent()) return;
          closeSource();
          handleError(error, owner);
          if (owner.isCurrent()) {
            emit({ error: error instanceof Error ? error.message : "连接失败，请稍后重试" });
            scheduleRetry();
          }
        } finally {
          if (running?.session === owner) running = undefined;
          if (owner.isCurrent()) emit({ connecting: false });
        }
      })();
      running = { session: owner, promise, full };
      return promise;
    };
    const login = (value: string) => {
      end();
      const password = value.trim();
      if (!password) { storePassword(""); return; }
      abort = new AbortController();
      const owner: AdminSession = {
        password, signal: abort.signal,
        isCurrent: () => !disposed && session === owner && !owner.signal.aborted
      };
      session = owner;
      void refresh();
    };
    const recover = () => {
      if (enabled && session && navigator.onLine && !document.hidden
          && (!source || Date.now() - lastRefreshAt > 1000)) void refresh();
    };
    const offline = () => { clearRetry(); closeSource(); };
    controls.current = {
      login,
      logout: () => { end(); storePassword(""); },
      refresh: () => refresh(),
      refreshStatus: () => refresh(false),
      currentSession: () => authenticated ? session : null,
      handleError,
      setLiveEnabled: (value) => {
        if (value === enabled) return;
        enabled = value;
        if (enabled) recover();
        else { clearRetry(); closeSource(); }
        if (enabled && session) openSource(session);
      }
    };
    window.addEventListener("online", recover);
    window.addEventListener("offline", offline);
    window.addEventListener("pageshow", recover);
    document.addEventListener("visibilitychange", recover);
    let saved = "";
    try { saved = localStorage.getItem(PASSWORD_KEY) || ""; } catch { /* Storage can be unavailable. */ }
    if (saved) login(saved);
    return () => {
      disposed = true; abort?.abort(); clearRetry(); closeSource(); controls.current = null;
      window.removeEventListener("online", recover);
      window.removeEventListener("offline", offline);
      window.removeEventListener("pageshow", recover);
      document.removeEventListener("visibilitychange", recover);
    };
  }, []);

  useEffect(() => { controls.current?.setLiveEnabled(options.autoRefresh); }, [options.autoRefresh]);

  // Stable methods let request callbacks depend on the session instead of render identity.
  const methods = useRef({
    login: (password: string) => controls.current?.login(password),
    logout: () => controls.current?.logout(),
    refresh: () => controls.current?.refresh() ?? Promise.resolve(),
    refreshStatus: () => controls.current?.refreshStatus() ?? Promise.resolve(),
    currentSession: () => controls.current?.currentSession() ?? null,
    handleError: (error: unknown, owner: AdminSession) => controls.current?.handleError(error, owner)
  });
  return { ...state, ...methods.current };
}
