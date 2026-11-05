// WhatsApp sender for the invoice API: links one WhatsApp number by scanning a QR (exactly like WhatsApp Web)
// and sends texts and PDFs from it. The API calls it; staff never talk to it directly.
//
// Built on Baileys (@whiskeysockets/baileys), which speaks the WhatsApp Web protocol. Two things to know:
//   1. It needs this long-lived process: the socket stays open between messages.
//   2. It is not the official WhatsApp Business API. WhatsApp may rate-limit or ban a number used for bulk
//      sending, so messages go out one at a time, a few seconds apart, and numbers not on WhatsApp are skipped.
//      Use a number the business is prepared to dedicate to this.
//
// The linked session is kept in WHATSAPP_AUTH_DIR (a Docker volume). Reconnects by itself after network drops
// and restarts; only when the phone unlinks it (or "Unlink" is pressed) does it need a new QR.
//
// Every request needs "Authorization: Bearer <WHATSAPP_TOKEN>".
//   GET  /status      {status, qr (PNG data URL while waiting for a scan), phone, name, lastError, updatedAt}
//   POST /connect     start linking: reconnects a saved session, or shows a new QR
//   POST /disconnect  close the connection, keep the session
//   POST /logout      unlink the number and delete the session; the next connect shows a QR
//   POST /send        {to: "9665XXXXXXXX", text, document?: {base64, fileName, mimetype}}

import http from "node:http";
import path from "node:path";
import { access, rm } from "node:fs/promises";
import QRCode from "qrcode";

const PORT = Number(process.env.PORT || 3210);
const TOKEN = process.env.WHATSAPP_TOKEN || "";
const AUTH_DIR = process.env.WHATSAPP_AUTH_DIR || path.join(process.cwd(), ".wa-auth");
/** Gap between two messages, so the number looks like a person and not a script. */
const MIN_GAP_MS = Number(process.env.WHATSAPP_MIN_GAP_MS || 2500);

if (!TOKEN) console.warn("WHATSAPP_TOKEN is not set: WhatsApp stays off and every request is refused.");

/** @type {{status: string, qr: string|null, phone: string|null, name: string|null, lastError: string|null, updatedAt: number}} */
let state = { status: "idle", qr: null, phone: null, name: null, lastError: null, updatedAt: Date.now() };
let sock = null;
let starting = null;
let reconnectTimer = null;
/** Set when an admin disconnected on purpose: no automatic reconnect. */
let manualStop = false;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const setState = (patch) => { state = { ...state, ...patch, updatedAt: Date.now() }; };
const linked = () => access(path.join(AUTH_DIR, "creds.json")).then(() => true, () => false);

async function connect() {
  if (sock && ["connected", "qr", "connecting"].includes(state.status)) return;
  if (starting) return starting;
  manualStop = false;
  starting = (async () => {
    setState({ status: "connecting", qr: null, lastError: null });
    const baileys = await import("@whiskeysockets/baileys");
    const pino = (await import("pino")).default;
    const { state: auth, saveCreds } = await baileys.useMultiFileAuthState(AUTH_DIR);
    const { version } = await baileys.fetchLatestBaileysVersion().catch(() => ({ version: undefined }));
    const s = baileys.makeWASocket({
      version,
      auth,
      logger: pino({ level: "silent" }),
      browser: baileys.Browsers.ubuntu("Chrome"),
      syncFullHistory: false,
      markOnlineOnConnect: false,
      generateHighQualityLinkPreview: false,
    });
    sock = s;
    s.ev.on("creds.update", saveCreds);
    s.ev.on("connection.update", async (u) => {
      if (u.qr) setState({ status: "qr", qr: await QRCode.toDataURL(u.qr, { margin: 1, width: 300 }) });
      if (u.connection === "open") {
        const me = s.user;
        setState({ status: "connected", qr: null, phone: me?.id ? me.id.split(":")[0].split("@")[0] : null, name: me?.name ?? null, lastError: null });
        console.log(`WhatsApp connected as ${state.phone}`);
      }
      if (u.connection === "close") {
        const code = u.lastDisconnect?.error?.output?.statusCode ?? 0;
        sock = null;
        if (code === baileys.DisconnectReason.loggedOut) {
          // Unlinked from the phone: wipe the session so the next connect shows a fresh QR instead of looping.
          await rm(AUTH_DIR, { recursive: true, force: true });
          setState({ status: "logged_out", qr: null, phone: null, name: null, lastError: "The number was unlinked from the phone. Scan a new QR to connect again." });
          console.log("WhatsApp logged out from the phone");
          return;
        }
        if (manualStop) {
          setState({ status: "idle", qr: null });
          return;
        }
        setState({ status: "connecting", qr: null, lastError: `Connection closed (${code || "network"}). Reconnecting…` });
        clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(() => { reconnectTimer = null; void connect(); }, 3000);
      }
    });
  })()
    .catch((err) => {
      sock = null;
      setState({ status: "error", lastError: err instanceof Error ? err.message : String(err) });
    })
    .finally(() => { starting = null; });
  return starting;
}

/** Reopens a saved session (never shows a QR by itself) and waits briefly for it. */
async function ensureConnected(timeoutMs = 15000) {
  if (sock && state.status === "connected") return true;
  if (manualStop || !(await linked())) return false;
  await connect();
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (sock && state.status === "connected") return true;
    if (["qr", "logged_out", "error"].includes(state.status)) return false;
    await sleep(250);
  }
  return false;
}

async function disconnect() {
  manualStop = true;
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  try { sock?.end(undefined); } catch { /* already closed */ }
  sock = null;
  setState({ status: "idle", qr: null });
}

async function logout() {
  manualStop = true;
  try { await sock?.logout(); } catch { /* socket may be gone; deleting the session is what matters */ }
  sock = null;
  await rm(AUTH_DIR, { recursive: true, force: true });
  setState({ status: "logged_out", qr: null, phone: null, name: null, lastError: null });
}

// One message at a time, MIN_GAP_MS apart.
let queue = Promise.resolve();
let lastSentAt = 0;

class SendError extends Error {
  constructor(code, message, httpStatus) {
    super(message);
    this.code = code;
    this.httpStatus = httpStatus;
  }
}

function send({ to, text, document }) {
  const job = queue.then(async () => {
    const digits = String(to || "").replace(/\D/g, "");
    if (digits.length < 10) throw new SendError("bad_number", "Not a usable phone number", 400);
    if (!(await ensureConnected())) throw new SendError("not_connected", "WhatsApp is not connected", 409);
    const wait = lastSentAt + MIN_GAP_MS + Math.random() * 1500 - Date.now();
    if (wait > 0) await sleep(wait);
    const jid = `${digits}@s.whatsapp.net`;
    const check = await sock.onWhatsApp(jid).catch(() => undefined);
    const target = check?.[0];
    // An empty answer means WhatsApp has no account for that number; no answer at all (lookup failed) still sends.
    if ((check && check.length === 0) || (target && !target.exists)) throw new SendError("not_on_whatsapp", "This number is not on WhatsApp", 422);
    const content = document
      ? { document: Buffer.from(document.base64, "base64"), fileName: document.fileName, mimetype: document.mimetype || "application/pdf", caption: text || undefined }
      : { text };
    await sock.sendMessage(target?.jid ?? jid, content);
    lastSentAt = Date.now();
    return { ok: true, to: digits };
  });
  queue = job.catch(() => {});
  return job;
}

function reply(res, code, body) {
  res.writeHead(code, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

async function readJson(req) {
  const chunks = [];
  let size = 0;
  for await (const c of req) {
    size += c.length;
    if (size > 25 * 1024 * 1024) throw new SendError("too_large", "Request is too large", 413);
    chunks.push(c);
  }
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString("utf8")) : {};
}

http
  .createServer(async (req, res) => {
    if (!TOKEN) return reply(res, 503, { error: "not_set_up", message: "WhatsApp is not set up on this server" });
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return reply(res, 401, { error: "unauthorized" });
    try {
      if (req.method === "GET" && req.url === "/status") return reply(res, 200, state);
      if (req.method === "POST" && req.url === "/connect") { await connect(); return reply(res, 200, state); }
      if (req.method === "POST" && req.url === "/disconnect") { await disconnect(); return reply(res, 200, state); }
      if (req.method === "POST" && req.url === "/logout") { await logout(); return reply(res, 200, state); }
      if (req.method === "POST" && req.url === "/send") return reply(res, 200, await send(await readJson(req)));
      return reply(res, 404, { error: "not_found" });
    } catch (err) {
      if (err instanceof SendError) return reply(res, err.httpStatus, { error: err.code, message: err.message });
      console.error(err);
      return reply(res, 500, { error: "failed", message: err instanceof Error ? err.message : String(err) });
    }
  })
  .listen(PORT, () => {
    console.log(`WhatsApp service on :${PORT}, session in ${AUTH_DIR}`);
    // After a restart, reopen the saved session without waiting for anyone to visit the admin page.
    if (TOKEN) void linked().then((yes) => yes && connect());
  });
