"use client";

import { useState } from "react";
import useSWR from "swr";
import { Plus, Trash2 } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import { Alert, Button, Card, CardHeader, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Role { id?: number; name: string; description?: string | null; permissions: Record<string, string[]>; users?: string[] }
interface User { id: number; email: string; fullName: string; role: string }

const MODULES: [string, string][] = [["sales", "Sales"], ["purchasing", "Purchasing"], ["inventory", "Inventory"], ["hr", "HR & payroll"], ["finance", "Finance & reports"]];
const ACTIONS: [string, string][] = [["view", "View"], ["create", "Create"], ["edit", "Edit"], ["delete", "Delete"], ["approve", "Approve / issue / cancel"]];

function Matrix({ role, onSave, onDelete }: { role: Role; onSave: (r: Role) => Promise<void>; onDelete?: () => void }) {
  const t = useT();
  const [r, setR] = useState<Role>(role);
  const [busy, setBusy] = useState(false);
  const toggle = (m: string, a: string) => {
    const cur = new Set(r.permissions[m] ?? []);
    if (cur.has(a)) cur.delete(a); else cur.add(a);
    setR({ ...r, permissions: { ...r.permissions, [m]: [...cur] } });
  };
  return (
    <Card className="mb-4">
      <div className="flex flex-wrap items-end gap-3 border-b border-line p-4">
        <Field label={t("Department / role")}><Input value={r.name} onChange={(e) => setR({ ...r, name: e.target.value })} placeholder={t("e.g. Purchase department")} /></Field>
        <Field label={t("Description")} optional className="flex-1"><Input value={r.description ?? ""} onChange={(e) => setR({ ...r, description: e.target.value })} /></Field>
        <Button loading={busy} disabled={!r.name.trim()} onClick={async () => { setBusy(true); try { await onSave(r); } finally { setBusy(false); } }}>{t("Save")}</Button>
        {onDelete && <Button variant="ghost" onClick={onDelete} aria-label={t("Delete")}><Trash2 className="size-4" aria-hidden /></Button>}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2 text-start font-medium">{t("Module")}</th>
            {ACTIONS.map(([a, l]) => <th key={a} className="px-3 py-2 text-center font-medium">{t(l)}</th>)}</tr></thead>
          <tbody className="divide-y divide-line">
            {MODULES.map(([m, l]) => (
              <tr key={m}><td className="px-4 py-2 font-medium">{t(l)}</td>
                {ACTIONS.map(([a]) => (
                  <td key={a} className="px-3 py-2 text-center">
                    <input type="checkbox" className="size-4 accent-[var(--color-brand)]" checked={(r.permissions[m] ?? []).includes(a)} onChange={() => toggle(m, a)} aria-label={`${l} ${a}`} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {r.users && r.users.length > 0 && <p className="px-4 py-2 text-xs text-muted">{t("Users")}: {r.users.join(", ")}</p>}
    </Card>
  );
}

export default function RolesPage() {
  const t = useT();
  const { data: roles, mutate } = useSWR<Role[]>("/settings/roles", fetcher);
  const { data: users, mutate: mutateUsers } = useSWR<(User & { customRoleId?: number })[]>("/users", fetcher);
  const [adding, setAdding] = useState(false);
  const [msg, setMsg] = useState<{ tone: "ok" | "bad"; text: string } | null>(null);
  const fail = (e: unknown) => setMsg({ tone: "bad", text: e instanceof ApiError ? e.message : t("Could not reach the server") });
  return (
    <>
      <PageHeader title={t("Departments & permissions")} subtitle={t("Give staff a department role to limit them to the modules and actions it allows. Admins always have full access.")}
                  actions={!adding && <Button onClick={() => setAdding(true)}><Plus className="size-4" aria-hidden /> {t("New role")}</Button>} />
      {msg && <div className="mb-4"><Alert tone={msg.tone}>{msg.text}</Alert></div>}
      {adding && <Matrix role={{ name: "", permissions: { sales: ["view"] } }} onSave={async (r) => { try { await api("POST", "/settings/roles", r); setAdding(false); mutate(); setMsg({ tone: "ok", text: t("Role saved") }); } catch (e) { fail(e); } }} />}
      {!roles ? <Spinner /> : roles.map((r) => (
        <Matrix key={r.id} role={r}
                onSave={async (x) => { try { await api("PUT", `/settings/roles/${r.id}`, x); mutate(); setMsg({ tone: "ok", text: t("Role saved") }); } catch (e) { fail(e); } }}
                onDelete={async () => { if (window.confirm(t("Delete role {name}?", { name: r.name }))) { await api("DELETE", `/settings/roles/${r.id}`); mutate(); } }} />
      ))}
      <Card>
        <CardHeader title={t("Assign roles to users")} />
        <table className="w-full text-sm"><tbody className="divide-y divide-line">
          {users?.filter((u) => u.role !== "ADMIN" && u.role !== "OPERATOR").map((u) => (
            <tr key={u.id}><td className="px-4 py-2">{u.fullName} <span className="text-xs text-muted">{u.email}</span></td>
              <td className="px-4 py-2">
                <Select className="w-64" value={roles?.find((r) => r.users?.includes(u.email))?.id ?? ""} onChange={async (e) => {
                  try { await api("PUT", `/settings/users/${u.id}/role`, { customRoleId: e.target.value ? Number(e.target.value) : null }); mutate(); mutateUsers(); }
                  catch (err) { fail(err); }
                }}>
                  <option value="">{t("No department role (built-in access)")}</option>
                  {roles?.map((r) => <option key={r.id} value={r.id}>{r.name}</option>)}
                </Select>
              </td></tr>
          ))}
        </tbody></table>
      </Card>
    </>
  );
}
