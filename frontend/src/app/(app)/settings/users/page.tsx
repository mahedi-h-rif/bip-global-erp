"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { KeyRound, Pencil, Plus } from "lucide-react";
import { ApiError, api, fetcher } from "@/lib/api";
import type { AccessOptions, AppUserRow, Me } from "@/lib/types";
import { dateTime } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";
import { statusText, useListTools } from "@/components/list-tools";

export default function UsersPage() {
  const t = useT();
  const { data: me } = useSWR<Me>("/auth/me", fetcher);
  const { data, error, mutate } = useSWR<AppUserRow[]>("/users", fetcher);
  const { toolbar, shown } = useListTools<AppUserRow>(data, { filters: [
    { label: "Access", get: (r) => (r.role === "ADMIN" || r.role === "OPERATOR" ? "Admin" : r.features ? "Custom access" : "Full access (old role)") },
    { label: "Status", get: (r) => (r.enabled ? "Active" : "Disabled") }] });
  const [editing, setEditing] = useState<AppUserRow | "new" | null>(null);
  const [resetting, setResetting] = useState<AppUserRow | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader
        title={t("Users")}
        subtitle={t("Every login of the company, what each one did, and their access")}
        actions={<Button onClick={() => { setEditing("new"); setResetting(null); setNotice(null); }}><Plus className="size-4" aria-hidden /> {t("Add user")}</Button>}
      />
      {notice && <div className="mb-4"><Alert tone="ok">{notice}</Alert></div>}
      {editing && (
        <UserForm key={editing === "new" ? "new" : editing.id} user={editing === "new" ? null : editing}
          onDone={(msg) => { setEditing(null); if (msg) { setNotice(msg); mutate(); } }} />
      )}
      {resetting && (
        <PasswordForm key={resetting.id} user={resetting}
          onDone={(msg) => { setResetting(null); if (msg) setNotice(msg); }} />
      )}
      <Card>
        {error && <div className="p-4"><Alert tone="bad">{error.message}</Alert></div>}
        {!data && !error && <Spinner />}
        {data?.length === 0 && <Empty title={t("No users yet")} />}
        {data && data.length > 0 && toolbar}
        {shown && shown.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-xs text-muted">
                  <th className="px-4 py-2.5 text-start font-medium">{t("Name")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Access")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Status")}</th>
                  <th className="px-4 py-2.5 text-end font-medium">{t("Actions this month")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Last active")}</th>
                  <th className="px-4 py-2.5 text-start font-medium">{t("Last sign-in")}</th>
                  <th className="px-4 py-2.5" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {shown.map((u) => {
                  const provider = u.role === "OPERATOR" && me?.role !== "OPERATOR";
                  return (
                    <tr key={u.id} className={u.enabled ? "" : "opacity-60"}>
                      <td className="px-4 py-2.5">
                        <Link href={`/settings/users/${u.id}`} className="font-medium text-brand hover:underline">{u.fullName}</Link>
                        <div className="text-xs text-muted" dir="ltr">{u.email}{u.id === me?.id ? ` · ${t("you")}` : ""}</div>
                      </td>
                      <td className="px-4 py-2.5">
                        {u.role === "ADMIN" || u.role === "OPERATOR" ? t(u.role === "OPERATOR" ? "Provider" : "Admin") : u.features ? (
                          <span className="text-xs">
                            <b className="text-sm font-medium">{t("{n} features", { n: String(u.features.length) })}</b>
                            <span className="block text-muted">{(u.actions ?? []).map((x) => t(ACTION_LABELS[x] ?? x)).join(" · ")} · {u.ownDataOnly ? t("own data") : t("all data")}</span>
                          </span>
                        ) : t("Full access (old role)")}
                      </td>
                      <td className="px-4 py-2.5">
                        <span className={`inline-flex rounded-full px-2 py-0.5 text-xs font-medium ${u.enabled ? "bg-ok/10 text-ok" : "bg-bad/10 text-bad"}`}>
                          {u.enabled ? t("Active") : t("Disabled")}
                        </span>
                      </td>
                      <td className="tabular px-4 py-2.5 text-end">{u.actionsThisMonth}</td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-muted">{u.lastActiveAt ? dateTime(u.lastActiveAt) : "–"}</td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-muted">{u.lastLoginAt ? dateTime(u.lastLoginAt) : t("Never")}</td>
                      <td className="whitespace-nowrap px-4 py-2.5 text-end">
                        <Link href={`/settings/users/${u.id}`} className="me-3 text-sm font-medium text-brand hover:underline">{t("Activity")}</Link>
                        {!provider && (
                          <>
                            <button type="button" title={t("Edit")} aria-label={t("Edit")} className="me-1 rounded-md p-1.5 text-muted hover:bg-canvas hover:text-ink"
                              onClick={() => { setEditing(u); setResetting(null); setNotice(null); window.scrollTo({ top: 0, behavior: "smooth" }); }}>
                              <Pencil className="size-4" />
                            </button>
                            <button type="button" title={t("Reset password")} aria-label={t("Reset password")} className="rounded-md p-1.5 text-muted hover:bg-canvas hover:text-ink"
                              onClick={() => { setResetting(u); setEditing(null); setNotice(null); window.scrollTo({ top: 0, behavior: "smooth" }); }}>
                              <KeyRound className="size-4" />
                            </button>
                          </>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="mt-3 text-xs text-muted">{t("Admins have every feature and see all data. Other users get only the features and actions ticked for them, and all data or only their own.")}</p>
    </>
  );
}

const ACTION_LABELS: Record<string, string> = { view: "View", create: "Create", update: "Update", delete: "Delete" };

/** Admin (everything) or custom access: ticked features, ticked actions, all data or only their own. */
function UserForm({ user, onDone }: { user: AppUserRow | null; onDone: (message?: string) => void }) {
  const t = useT();
  const { data: options } = useSWR<AccessOptions>("/users/access-options", fetcher);
  const [fullName, setFullName] = useState(user?.fullName ?? "");
  const [email, setEmail] = useState(user?.email ?? "");
  const [admin, setAdmin] = useState(user?.role === "ADMIN" || user?.role === "OPERATOR");
  const [features, setFeatures] = useState<string[] | null>(user?.features ?? null);
  const [actions, setActions] = useState<string[]>(
    user?.actions ?? (user?.role === "VIEWER" ? ["view"] : ["view", "create", "update", "delete"]));
  const [ownDataOnly, setOwnDataOnly] = useState(user?.ownDataOnly || user?.role === "BRANCH");
  const [enabled, setEnabled] = useState(user?.enabled ?? true);
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  // Users made before custom access start with every feature ticked, so nothing is taken away by accident.
  const ticked = features ?? (user && !admin ? options?.features.map((f) => f.key) ?? [] : []);
  const sections = options ? [...new Set(options.features.map((f) => f.section))] : [];
  const toggle = (keys: string[], on: boolean) =>
    setFeatures([...new Set(on ? [...ticked, ...keys] : ticked.filter((k) => !keys.includes(k)))]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const access = admin
      ? { role: "ADMIN", features: null, actions: null, ownDataOnly: false }
      : { role: "ACCOUNTANT", features: ticked, actions: [...new Set(["view", ...actions])], ownDataOnly };
    try {
      if (user) {
        await api("PUT", `/users/${user.id}`, { fullName, enabled, ...access });
        onDone(t("Saved."));
      } else {
        await api("POST", "/users", { email, fullName, password, ...access });
        onDone(t("User added. They can sign in with this email and password."));
      }
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={user ? t("Edit {name}", { name: user.fullName }) : t("Add user")} />
      <form onSubmit={submit} className="grid gap-4 p-5 md:grid-cols-2">
        {error && <div className="md:col-span-2"><Alert tone="bad" title={t("Not saved")}>{error.fields ? Object.values(error.fields).join(", ") : error.message}</Alert></div>}
        <Field label={t("Full name")}><Input required value={fullName} onChange={(e) => setFullName(e.target.value)} /></Field>
        <Field label={t("Email (sign-in)")} hint={user ? t("The sign-in email cannot be changed: records are kept under it.") : undefined}>
          <Input required type="email" dir="ltr" disabled={!!user} value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        {user ? (
          <Field label={t("Status")} hint={t("A disabled user is signed out at once and cannot sign in. Their records stay.")}>
            <Select value={enabled ? "1" : "0"} onChange={(e) => setEnabled(e.target.value === "1")}>
              <option value="1">{t("Active")}</option>
              <option value="0">{t("Disabled")}</option>
            </Select>
          </Field>
        ) : (
          <Field label={t("Password")} hint={t("At least 10 characters. Share it with the user privately.")}>
            <Input required minLength={10} type="text" autoComplete="new-password" dir="ltr" value={password} onChange={(e) => setPassword(e.target.value)} />
          </Field>
        )}

        <fieldset className="md:col-span-2">
          <legend className="mb-2 text-sm font-medium">{t("Access")}</legend>
          <div className="flex flex-wrap gap-4 text-sm">
            <label className="inline-flex items-center gap-2"><input type="radio" name="kind" checked={admin} onChange={() => setAdmin(true)} className="accent-[var(--color-brand)]" />
              <span><b>{t("Admin")}</b> <span className="text-muted">· {t("every feature, every action, all data")}</span></span></label>
            <label className="inline-flex items-center gap-2"><input type="radio" name="kind" checked={!admin} onChange={() => setAdmin(false)} className="accent-[var(--color-brand)]" />
              <span><b>{t("Custom access")}</b> <span className="text-muted">· {t("choose below")}</span></span></label>
          </div>
        </fieldset>

        {!admin && (
          <>
            <fieldset className="rounded-lg border border-line p-4 md:col-span-2">
              <legend className="px-1 text-sm font-medium">{t("Features this user can open")}</legend>
              {!options ? <Spinner /> : (
                <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {sections.map((sec) => {
                    const keys = options.features.filter((f) => f.section === sec).map((f) => f.key);
                    const all = keys.every((k) => ticked.includes(k));
                    return (
                      <div key={sec}>
                        <label className="mb-1.5 flex items-center gap-2 text-xs font-semibold uppercase tracking-wide text-muted">
                          <input type="checkbox" checked={all} onChange={(e) => toggle(keys, e.target.checked)} className="size-4 accent-[var(--color-brand)]" />
                          {t(sec)}
                        </label>
                        <div className="space-y-1 ps-6">
                          {options.features.filter((f) => f.section === sec).map((f) => (
                            <label key={f.key} className="flex items-center gap-2 text-sm">
                              <input type="checkbox" checked={ticked.includes(f.key)} onChange={(e) => toggle([f.key], e.target.checked)} className="size-4 accent-[var(--color-brand)]" />
                              {t(f.label)}
                            </label>
                          ))}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </fieldset>

            <fieldset className="rounded-lg border border-line p-4">
              <legend className="px-1 text-sm font-medium">{t("What this user can do")}</legend>
              <div className="flex flex-wrap gap-4 text-sm">
                {(options?.actions ?? ["view", "create", "update", "delete"]).map((a) => (
                  <label key={a} className="inline-flex items-center gap-2">
                    <input type="checkbox" disabled={a === "view"} checked={a === "view" || actions.includes(a)}
                      onChange={(e) => setActions(e.target.checked ? [...actions, a] : actions.filter((x) => x !== a))}
                      className="size-4 accent-[var(--color-brand)]" />
                    {t(ACTION_LABELS[a] ?? a)}
                  </label>
                ))}
              </div>
              <p className="mt-2 text-xs text-muted">{t("View is always on. Issuing, approving and cancelling count as Update.")}</p>
            </fieldset>

            <fieldset className="rounded-lg border border-line p-4">
              <legend className="px-1 text-sm font-medium">{t("Data this user can see")}</legend>
              <div className="space-y-1.5 text-sm">
                <label className="flex items-center gap-2"><input type="radio" name="scope" checked={!ownDataOnly} onChange={() => setOwnDataOnly(false)} className="accent-[var(--color-brand)]" />{t("Access all data")}</label>
                <label className="flex items-center gap-2"><input type="radio" name="scope" checked={ownDataOnly} onChange={() => setOwnDataOnly(true)} className="accent-[var(--color-brand)]" />{t("Access only own data")}</label>
              </div>
              <p className="mt-2 text-xs text-muted">{t("Own data: only the invoices, quotations, orders, deliveries, agreements, payments and cash book entries this user created.")}</p>
            </fieldset>
          </>
        )}

        <div className="flex gap-2 md:col-span-2">
          <Button type="submit" loading={busy} disabled={!admin && ticked.length === 0}>{user ? t("Save") : t("Add user")}</Button>
          <Button type="button" variant="secondary" onClick={() => onDone()}>{t("Cancel")}</Button>
        </div>
      </form>
    </Card>
  );
}

function PasswordForm({ user, onDone }: { user: AppUserRow; onDone: (message?: string) => void }) {
  const t = useT();
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("POST", `/users/${user.id}/password`, { password });
      onDone(t("Password changed for {name}.", { name: user.fullName }));
    } catch (err) {
      setError(err as ApiError);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="mb-6">
      <CardHeader title={t("Reset password for {name}", { name: user.fullName })} />
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3 p-5">
        {error && <div className="w-full"><Alert tone="bad" title={t("Not saved")}>{error.fields ? Object.values(error.fields).join(", ") : error.message}</Alert></div>}
        <Field label={t("New password")} hint={t("At least 10 characters. Share it with the user privately.")} className="min-w-[260px] flex-1">
          <Input required minLength={10} type="text" autoComplete="new-password" dir="ltr" value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <Button type="submit" loading={busy}>{t("Change password")}</Button>
        <Button type="button" variant="secondary" onClick={() => onDone()}>{t("Cancel")}</Button>
      </form>
    </Card>
  );
}
