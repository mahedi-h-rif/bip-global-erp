"use client";

/** Error returned by the Spring API ({ code, message, fields }). */
export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public fields?: Record<string, string>,
  ) {
    super(message);
  }
}

function xsrfToken(): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith("XSRF-TOKEN="))
    ?.split("=")[1];
}

/** Ensures the XSRF-TOKEN cookie exists before the first state-changing request. */
async function ensureXsrf() {
  if (!xsrfToken()) await fetch("/api/v1/auth/csrf", { credentials: "same-origin" });
}

export async function api<T>(method: string, path: string, body?: unknown): Promise<T> {
  if (method !== "GET") await ensureXsrf();
  const token = xsrfToken();
  const res = await fetch(`/api/v1${path}`, {
    method,
    credentials: "same-origin",
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token && method !== "GET" ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401 && !path.startsWith("/auth/")) {
    // Plain module outside React: a hard navigation also clears any stale client state.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.href = `/login?next=${encodeURIComponent(window.location.pathname)}`;
    throw new ApiError(401, "unauthorized", "Session expired");
  }
  if (res.status === 204) return undefined as T;

  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    throw new ApiError(res.status, data?.code ?? "error", data?.message ?? `Request failed (${res.status})`, data?.fields);
  }
  return data as T;
}

/** Loads a PDF endpoint into a hidden frame and opens the browser's print dialog for it. */
export async function printFile(path: string) {
  const res = await fetch(`/api/v1${path}`, { credentials: "same-origin" });
  if (!res.ok) {
    const data = await res.json().catch(() => undefined);
    throw new ApiError(res.status, data?.code ?? "error", data?.message ?? "Could not load the document");
  }
  const url = URL.createObjectURL(await res.blob());
  const frame = document.createElement("iframe");
  frame.style.cssText = "position:fixed;right:0;bottom:0;width:0;height:0;border:0";
  frame.src = url;
  frame.onload = () => {
    frame.contentWindow?.focus();
    frame.contentWindow?.print();
    // Keep the frame while the print dialog is open; clean up afterwards.
    setTimeout(() => {
      frame.remove();
      URL.revokeObjectURL(url);
    }, 60_000);
  };
  document.body.appendChild(frame);
}

/** Opens a PDF endpoint in a new browser tab. */
export async function openFile(path: string) {
  const tab = window.open("", "_blank");
  const res = await fetch(`/api/v1${path}`, { credentials: "same-origin" });
  if (!res.ok) {
    tab?.close();
    const data = await res.json().catch(() => undefined);
    throw new ApiError(res.status, data?.code ?? "error", data?.message ?? "Could not load the document");
  }
  const url = URL.createObjectURL(await res.blob());
  if (tab) tab.location.href = url;
  else window.location.href = url;
}

/** PUTs a file as the raw request body, with its own content type (e.g. the company logo). */
export async function uploadFile<T>(path: string, file: File): Promise<T> {
  await ensureXsrf();
  const token = xsrfToken();
  const res = await fetch(`/api/v1${path}`, {
    method: "PUT",
    credentials: "same-origin",
    headers: { "Content-Type": file.type, ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) },
    body: file,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    const fieldMsg = data?.fields ? Object.values(data.fields).join(", ") : undefined;
    throw new ApiError(res.status, data?.code ?? "error", fieldMsg ?? data?.message ?? `Upload failed (${res.status})`, data?.fields);
  }
  return data as T;
}

export const fetcher =<T,>(path: string) => api<T>("GET", path);

/** Downloads a file endpoint (PDF / XML) using the session cookie. */
export async function download(path: string, fallbackName: string) {
  const res = await fetch(`/api/v1${path}`, { credentials: "same-origin" });
  if (!res.ok) {
    const data = await res.json().catch(() => undefined);
    throw new ApiError(res.status, data?.code ?? "error", data?.message ?? "Download failed");
  }
  saveBlob(await res.blob(), res, fallbackName);
}

/** Downloads a file the API builds from a JSON body (e.g. a PDF preview of unsaved edits). */
export async function downloadPost(path: string, body: unknown, fallbackName: string) {
  await ensureXsrf();
  const token = xsrfToken();
  const res = await fetch(`/api/v1${path}`, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", ...(token ? { "X-XSRF-TOKEN": decodeURIComponent(token) } : {}) },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const data = await res.json().catch(() => undefined);
    const fieldMsg = data?.fields ? Object.values(data.fields).join(", ") : undefined;
    throw new ApiError(res.status, data?.code ?? "error", fieldMsg ?? data?.message ?? "Download failed", data?.fields);
  }
  saveBlob(await res.blob(), res, fallbackName);
}

function saveBlob(blob: Blob, res: Response, fallbackName: string) {
  const name = /filename="?([^"]+)"?/.exec(res.headers.get("Content-Disposition") ?? "")?.[1] ?? fallbackName;
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement("a"), { href: url, download: name });
  a.click();
  URL.revokeObjectURL(url);
}

/**
 * Shares a file endpoint through the device's share sheet (WhatsApp, email…) where the browser can share
 * files; elsewhere it is downloaded so it can be attached by hand. Returns "shared" or "downloaded".
 */
export async function shareFile(path: string, name: string, type: string): Promise<"shared" | "downloaded"> {
  const res = await fetch(`/api/v1${path}`, { credentials: "same-origin" });
  if (!res.ok) {
    const data = await res.json().catch(() => undefined);
    throw new ApiError(res.status, data?.code ?? "error", data?.message ?? "Could not load the document");
  }
  const blob = await res.blob();
  const file = new File([blob], name, { type });
  if (typeof navigator.canShare === "function" && navigator.canShare({ files: [file] })) {
    try {
      await navigator.share({ files: [file], title: name });
      return "shared";
    } catch (e) {
      // Closing the share sheet is not an error.
      if (e instanceof DOMException && e.name === "AbortError") return "shared";
    }
  }
  saveBlob(blob, res, name);
  return "downloaded";
}
