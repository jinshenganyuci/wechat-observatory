export const MAX_FILE_BYTES = 300 * 1024 * 1024;

const MEDIA_EXTENSIONS = new Set([
  "jpg", "jpeg", "bmp", "png", "tif", "tiff", "heic", "heif", "webp",
  "avi", "flv", "m4v", "mkv", "mov", "mp4", "mts", "webm", "wmv"
]);

export type FileSendScope = { device: string; ownerWxid: string; generation: number; wxid: string };
export type FileSendDraft = {
  file?: File; name: string; size: number; key: string; scope: FileSendScope;
  stage: "ready" | "submitting" | "unknown" | "rejected";
  message: string;
};

const PENDING_FILE_KEY = "wgc_pending_file_request";

/** Store only retry identity, never file bytes or credentials. Write before the POST. */
export function persistFileRequest(draft: FileSendDraft, storage: Pick<Storage, "setItem"> = sessionStorage) {
  storage.setItem(PENDING_FILE_KEY, JSON.stringify({ name: draft.name, size: draft.size,
    key: draft.key, scope: draft.scope }));
}

export function restoreFileRequest(storage: Pick<Storage, "getItem"> = sessionStorage): FileSendDraft | null {
  try {
    const value = JSON.parse(storage.getItem(PENDING_FILE_KEY) || "null");
    if (!value || typeof value.name !== "string" || !value.name || !Number.isSafeInteger(value.size)
      || value.size <= 0 || value.size > MAX_FILE_BYTES || !/^file-[a-f0-9]{32}$/.test(value.key)
      || !value.scope || ![value.scope.device, value.scope.ownerWxid, value.scope.wxid].every(v => typeof v === "string" && !!v)
      || !Number.isSafeInteger(value.scope.generation) || value.scope.generation <= 0) return null;
    return { name: value.name, size: value.size, key: value.key, scope: value.scope,
      stage: "unknown", message: "原请求结果未确认。请重新选择原文件，以原请求标识核查或重试。" };
  } catch { return null; }
}

export function clearFileRequest(storage: Pick<Storage, "getItem" | "removeItem"> = sessionStorage, expectedKey?: string) {
  try {
    if (expectedKey && JSON.parse(storage.getItem(PENDING_FILE_KEY) || "null")?.key !== expectedKey) return;
    storage.removeItem(PENDING_FILE_KEY);
  } catch { /* No new request is created by cleanup. */ }
}

export function fileValidationError(file: Pick<File, "name" | "size">) {
  if (file.size <= 0) return "请选择非空文件。";
  if (file.size > MAX_FILE_BYTES) return "文件不能超过 300 MiB。";
  if (!file.name || file.name === "." || file.name === ".."
    || new TextEncoder().encode(file.name).length > 240
    || /[\/\\\p{Cc}\uD800-\uDFFF]/u.test(file.name)
    || /^\p{White_Space}|\p{White_Space}$/u.test(file.name)) {
    return "文件名不能超过 240 UTF-8 字节，且不能包含路径分隔符、控制字符或首尾空白。";
  }
  const lastDot = file.name.lastIndexOf(".");
  if (lastDot >= 0 && MEDIA_EXTENSIONS.has(file.name.slice(lastDot + 1).toLowerCase())) {
    return "暂不支持图片或视频文件，请选择文档、压缩包等普通文件。";
  }
  return "";
}

export function sameFileScope(left: FileSendScope, right: FileSendScope) {
  return left.device === right.device && left.ownerWxid === right.ownerWxid
    && left.generation === right.generation && left.wxid === right.wxid;
}

export function fileDraftMatchesScope(draft: FileSendDraft, current: FileSendScope) {
  // A submitted request must keep its old generation. The backend resolves its
  // idempotency record before testing current generation, never creating a new job.
  return draft.scope.device === current.device && draft.scope.ownerWxid === current.ownerWxid
    && draft.scope.wxid === current.wxid && (draft.stage !== "ready" || sameFileScope(draft.scope, current));
}

export function fileTargetSupported(wxid: string) {
  return wxid === "filehelper" || wxid.endsWith("@chatroom");
}

export function newFileRequestKey() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return "file-" + Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
}

export function formatFileSize(bytes: number) {
  if (!Number.isFinite(bytes) || bytes < 0) return "大小未知";
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}
