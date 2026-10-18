import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import { ApiError, getApiKeys } from "../../src/api";
import { useAdminConnection } from "../../src/useAdminConnection";

function Connection() {
  const [draft, setDraft] = useState("");
  const [enabled, setEnabled] = useState(true);
  const [label, setLabel] = useState("first");
  const [modules, setModules] = useState("");
  const [message, setMessage] = useState("");
  const [refreshes, setRefreshes] = useState(0);
  const connection = useAdminConnection({
    autoRefresh: enabled,
    onAuthenticated: (items, session) => {
      if (session.isCurrent()) setModules(items.map((item) => item.device).join(","));
    },
    onRefresh: async (session) => {
      await getApiKeys(session.password);
      if (session.isCurrent()) setRefreshes((count) => count + 1);
    },
    onMessage: (event) => setMessage(`${label}:${event.device}`),
    onReset: () => { setModules(""); setMessage(""); }
  });
  return <>
    <input aria-label="password" value={draft} onChange={(event) => setDraft(event.target.value)} />
    <button onClick={() => connection.login(draft)}>Login</button>
    <button onClick={connection.logout}>Logout</button>
    <button onClick={() => void connection.refresh()}>Refresh</button>
    <button onClick={() => void connection.refreshStatus()}>Refresh status</button>
    <button onClick={() => {
      const session = connection.currentSession();
      if (session) connection.handleError(new ApiError(409, "operation conflict"), session);
    }}>Operation error</button>
    <button onClick={() => setLabel("second")}>Change selection</button>
    <label><input type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} />Live</label>
    <output data-testid="password">{connection.password}</output>
    <output data-testid="modules">{modules}</output>
    <output data-testid="message">{message}</output>
    <output data-testid="live">{String(connection.liveConnected)}</output>
    <output data-testid="connecting">{String(connection.connecting)}</output>
    <output data-testid="refreshes">{refreshes}</output>
    <output data-testid="error">{connection.error}</output>
  </>;
}

function Harness() {
  const [mounted, setMounted] = useState(true);
  return <><button onClick={() => setMounted((value) => !value)}>Toggle mount</button>{mounted && <Connection />}</>;
}
const content = <Harness />;
createRoot(document.getElementById("root")!).render(
  new URLSearchParams(location.search).has("strict") ? <React.StrictMode>{content}</React.StrictMode> : content
);
