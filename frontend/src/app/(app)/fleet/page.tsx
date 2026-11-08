"use client";

import { useState, type FormEvent } from "react";
import useSWR from "swr";
import { Fuel, Pencil, Plus, Route, Truck, X } from "lucide-react";
import { api, ApiError, fetcher } from "@/lib/api";
import type { DeliveryNote, Employee } from "@/lib/types";
import { date, money, todayKsa } from "@/lib/format";
import { Alert, Button, Card, CardHeader, Empty, Field, Input, PageHeader, Select, Spinner } from "@/components/ui";
import { useT } from "@/lib/i18n";

interface Vehicle { id?: number; plate: string; name: string; vehicleType?: string; capacity?: string | null; modelYear?: number | null; driverEmployeeId?: number | null; driverName?: string | null;
  istimaraExpiry?: string | null; insuranceExpiry?: string | null; mvpiExpiry?: string | null; odometer?: number | string | null; active?: boolean; notes?: string | null; nextExpiryDays?: number | null; costThisMonth?: number }
interface Log { id: number; vehicleId: number; plate: string; date: string; kind: string; amount: number; liters: number | null; odometer: number | null; description: string | null }
interface Cost { id: number; kind: string; amount: number; description: string | null }
interface Trip { id: number; number: string; vehicleId: number; plate: string; driverName: string | null; tripDate: string; origin: string | null; destination: string | null; status: string;
  startOdometer: number | null; endOdometer: number | null; notes: string | null; deliveries: { id: number; number: string; status: string; client: string; value: number }[];
  costs: Cost[]; totalCost: number; revenue: number; profit: number }

type Tab = "vehicles" | "logs" | "trips";
const errText = (e: unknown, f: string) => (e instanceof ApiError ? (e.fields ? Object.values(e.fields).join(", ") : e.message) : f);
const tripTone: Record<string, string> = { PLANNED: "bg-blue-50 text-blue-700", IN_TRANSIT: "bg-amber-50 text-amber-700", COMPLETED: "bg-emerald-50 text-emerald-700", CANCELLED: "bg-slate-100 text-slate-500" };

function Expiry({ d }: { d: string | null | undefined }) {
  const t = useT();
  if (!d) return <span className="text-muted">—</span>;
  const days = Math.round((Date.parse(d) - Date.parse(todayKsa())) / 86400000);
  return <span className={days < 0 ? "font-semibold text-bad" : days <= 30 ? "font-semibold text-amber-700" : ""}>{date(d)}{days <= 30 && <span className="ms-1 text-[11px]">({days < 0 ? t("expired") : t("{n} d", { n: String(days) })})</span>}</span>;
}

function VehicleForm({ initial, drivers, onDone }: { initial: Vehicle; drivers: Employee[]; onDone: () => void }) {
  const t = useT();
  const [v, setV] = useState<Vehicle>(initial);
  const [error, setError] = useState<string | null>(null);
  const f = (k: keyof Vehicle) => (e: { target: { value: string } }) => setV({ ...v, [k]: e.target.value || null });
  async function save(e: FormEvent) {
    e.preventDefault();
    setError(null);
    const body = { ...v, driverEmployeeId: v.driverEmployeeId ? Number(v.driverEmployeeId) : null, modelYear: v.modelYear ? Number(v.modelYear) : null, odometer: v.odometer ? Number(v.odometer) : null };
    try { if (v.id) await api("PUT", `/fleet/vehicles/${v.id}`, body); else await api("POST", "/fleet/vehicles", body); onDone(); }
    catch (err) { setError(errText(err, t("Could not reach the server"))); }
  }
  return (
    <Card className="mb-4">
      <CardHeader title={v.id ? t("Edit vehicle") : t("New vehicle")} />
      <form onSubmit={save} className="space-y-4 p-5">
        {error && <Alert tone="bad">{error}</Alert>}
        <div className="grid gap-4 md:grid-cols-4">
          <Field label={t("Plate")}><Input required dir="ltr" value={v.plate} onChange={f("plate")} /></Field>
          <Field label={t("Make / model")}><Input required value={v.name} onChange={f("name")} /></Field>
          <Field label={t("Type")}><Select value={v.vehicleType ?? "TRUCK"} onChange={f("vehicleType")}>{["TRUCK", "VAN", "PICKUP", "CAR", "TRAILER", "FORKLIFT"].map((x) => <option key={x} value={x}>{t(x.charAt(0) + x.slice(1).toLowerCase())}</option>)}</Select></Field>
          <Field label={t("Capacity")} optional><Input value={v.capacity ?? ""} onChange={f("capacity")} placeholder="5 t" /></Field>
          <Field label={t("Driver")} optional><Select value={v.driverEmployeeId ?? ""} onChange={f("driverEmployeeId")}><option value="">—</option>{drivers.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</Select></Field>
          <Field label={t("Istimara expiry")} optional><Input type="date" value={v.istimaraExpiry ?? ""} onChange={f("istimaraExpiry")} /></Field>
          <Field label={t("Insurance expiry")} optional><Input type="date" value={v.insuranceExpiry ?? ""} onChange={f("insuranceExpiry")} /></Field>
          <Field label={t("Inspection (Fahas) expiry")} optional><Input type="date" value={v.mvpiExpiry ?? ""} onChange={f("mvpiExpiry")} /></Field>
          <Field label={t("Model year")} optional><Input type="number" value={v.modelYear ?? ""} onChange={f("modelYear")} /></Field>
          <Field label={t("Odometer (km)")} optional><Input type="number" step="any" value={v.odometer ?? ""} onChange={f("odometer")} /></Field>
        </div>
        <div className="flex gap-2"><Button type="submit">{t("Save vehicle")}</Button><Button type="button" variant="ghost" onClick={onDone}>{t("Cancel")}</Button></div>
      </form>
    </Card>
  );
}

function TripCard({ trip, onChange }: { trip: Trip; onChange: () => void }) {
  const t = useT();
  const { data: notes } = useSWR<DeliveryNote[]>(trip.status === "PLANNED" || trip.status === "IN_TRANSIT" ? "/delivery-notes" : null, fetcher);
  const [cost, setCost] = useState({ kind: "FUEL", amount: "", description: "" });
  const [end, setEnd] = useState("");
  const [error, setError] = useState<string | null>(null);
  const open = trip.status === "PLANNED" || trip.status === "IN_TRANSIT";
  const act = async (fn: () => Promise<unknown>) => { setError(null); try { await fn(); onChange(); } catch (e) { setError(errText(e, t("Could not reach the server"))); } };
  return (
    <Card className="mb-4">
      <CardHeader title={<span className="flex flex-wrap items-center gap-2"><span className="tabular">{trip.number}</span><span className={`rounded px-2 py-0.5 text-xs ${tripTone[trip.status]}`}>{t(trip.status.replace("_", " ").toLowerCase())}</span>
        <span className="text-sm font-normal text-muted">{trip.plate} · {trip.driverName ?? "—"} · {date(trip.tripDate)} · {[trip.origin, trip.destination].filter(Boolean).join(" → ")}</span></span>}
        action={<span className="tabular text-sm">{t("Revenue")} {money(trip.revenue)} · {t("Cost")} {money(trip.totalCost)} · <b className={Number(trip.profit) < 0 ? "text-bad" : "text-ok"}>{t("Profit")} {money(trip.profit)}</b></span>} />
      <div className="grid gap-4 p-4 lg:grid-cols-2">
        {error && <div className="lg:col-span-2"><Alert tone="bad">{error}</Alert></div>}
        <div>
          <p className="mb-1 text-xs font-medium text-muted">{t("Deliveries on this trip")}</p>
          {trip.deliveries.length === 0 ? <p className="text-sm text-muted">{t("None yet")}</p> : trip.deliveries.map((d) => (
            <div key={d.id} className="flex items-center justify-between border-b border-line py-1 text-sm"><span>{d.number} · <span dir="auto">{d.client}</span></span>
              <span className="flex items-center gap-2 tabular">{money(d.value)}{open && <button onClick={() => act(() => api("DELETE", `/fleet/trips/${trip.id}/deliveries/${d.id}`))} aria-label={t("Remove")}><X className="size-3.5" /></button>}</span></div>
          ))}
          {open && notes && (
            <Select className="mt-2" value="" onChange={(e) => e.target.value && act(() => api("POST", `/fleet/trips/${trip.id}/deliveries/${e.target.value}`))} aria-label={t("Add delivery note")}>
              <option value="">{t("Add a delivery note…")}</option>
              {notes.filter((n) => n.status !== "CANCELLED" && !trip.deliveries.some((d) => d.id === n.id)).map((n) => <option key={n.id} value={n.id}>{n.number} · {n.customerName ?? n.clientName}</option>)}
            </Select>
          )}
        </div>
        <div>
          <p className="mb-1 text-xs font-medium text-muted">{t("Trip costs (posted to expenses)")}</p>
          {trip.costs.map((c) => <div key={c.id} className="flex justify-between border-b border-line py-1 text-sm"><span>{t(c.kind.replace("_", " ").toLowerCase())}{c.description && ` · ${c.description}`}</span><span className="tabular">{money(c.amount)}</span></div>)}
          {trip.status !== "CANCELLED" && (
            <form className="mt-2 flex flex-wrap gap-2" onSubmit={(e) => { e.preventDefault(); void act(() => api("POST", `/fleet/trips/${trip.id}/costs`, { ...cost, amount: Number(cost.amount) })); setCost({ kind: "FUEL", amount: "", description: "" }); }}>
              <Select className="w-36" value={cost.kind} onChange={(e) => setCost({ ...cost, kind: e.target.value })}>{["FUEL", "TOLL", "DRIVER_ALLOWANCE", "LOADING", "OTHER"].map((k) => <option key={k} value={k}>{t(k.replace("_", " ").toLowerCase())}</option>)}</Select>
              <Input className="w-28" type="number" min="0.01" step="0.01" required value={cost.amount} onChange={(e) => setCost({ ...cost, amount: e.target.value })} placeholder="SAR" />
              <Input className="flex-1" value={cost.description} onChange={(e) => setCost({ ...cost, description: e.target.value })} placeholder={t("Note")} />
              <Button type="submit" variant="secondary">{t("Add")}</Button>
            </form>
          )}
        </div>
        {open && (
          <div className="flex flex-wrap items-end gap-2 lg:col-span-2">
            {trip.status === "PLANNED" && <Button variant="secondary" onClick={() => act(() => api("POST", `/fleet/trips/${trip.id}/status`, { status: "IN_TRANSIT" }))}>{t("Start trip")}</Button>}
            <Field label={t("End odometer")} optional><Input className="w-32" type="number" value={end} onChange={(e) => setEnd(e.target.value)} /></Field>
            <Button onClick={() => act(() => api("POST", `/fleet/trips/${trip.id}/status`, { status: "COMPLETED", endOdometer: end ? Number(end) : null }))}>{t("Complete trip")}</Button>
            <Button variant="ghost" onClick={() => act(() => api("POST", `/fleet/trips/${trip.id}/status`, { status: "CANCELLED" }))}>{t("Cancel trip")}</Button>
          </div>
        )}
      </div>
    </Card>
  );
}

export default function FleetPage() {
  const t = useT();
  const [tab, setTab] = useState<Tab>("vehicles");
  const { data: vehicles, mutate } = useSWR<Vehicle[]>("/fleet/vehicles?all=true", fetcher);
  const { data: drivers } = useSWR<Employee[]>("/hr/employees", fetcher);
  const { data: logs, mutate: mutateLogs } = useSWR<Log[]>(tab === "logs" ? "/fleet/logs" : null, fetcher);
  const { data: trips, mutate: mutateTrips } = useSWR<Trip[]>(tab === "trips" ? "/fleet/trips" : null, fetcher);
  const [editing, setEditing] = useState<Vehicle | null>(null);
  const [log, setLog] = useState({ vehicleId: "", kind: "FUEL", amount: "", liters: "", odometer: "", description: "", date: todayKsa() });
  const [trip, setTrip] = useState({ vehicleId: "", origin: "", destination: "", tripDate: todayKsa() });
  const [error, setError] = useState<string | null>(null);
  const tabs: { id: Tab; label: string; icon: typeof Truck }[] = [{ id: "vehicles", label: "Vehicles", icon: Truck }, { id: "logs", label: "Fuel & maintenance", icon: Fuel }, { id: "trips", label: "Trips", icon: Route }];
  return (
    <>
      <PageHeader title={t("Fleet & trips")} subtitle={t("Vehicles, registration / insurance / inspection expiry, fuel and maintenance, and the profit of each trip.")} />
      <div className="mb-4 flex flex-wrap gap-2">{tabs.map((x) => <button key={x.id} onClick={() => setTab(x.id)} className={`inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm font-medium ${tab === x.id ? "border-brand bg-brand text-white" : "border-line bg-white"}`}><x.icon className="size-4" aria-hidden /> {t(x.label)}</button>)}</div>
      {error && <div className="mb-4"><Alert tone="bad">{error}</Alert></div>}
      {!vehicles ? <Spinner /> : tab === "vehicles" ? (
        <>
          {editing && drivers && <VehicleForm key={editing.id ?? "new"} initial={editing} drivers={drivers} onDone={() => { setEditing(null); mutate(); }} />}
          <Card>
            <CardHeader title={t("Vehicles")} action={!editing && <Button onClick={() => setEditing({ plate: "", name: "", vehicleType: "TRUCK" })}><Plus className="size-4" aria-hidden /> {t("New vehicle")}</Button>} />
            {vehicles.length === 0 ? <Empty title={t("No vehicles yet")} /> : (
              <div className="overflow-x-auto"><table className="w-full text-sm">
                <thead><tr className="border-b border-line text-xs text-muted"><th className="px-4 py-2 text-start font-medium">{t("Plate")}</th><th className="px-4 py-2 text-start font-medium">{t("Vehicle")}</th><th className="px-4 py-2 text-start font-medium">{t("Driver")}</th><th className="px-4 py-2 text-start font-medium">{t("Istimara")}</th><th className="px-4 py-2 text-start font-medium">{t("Insurance")}</th><th className="px-4 py-2 text-start font-medium">{t("Fahas")}</th><th className="px-4 py-2 text-end font-medium">{t("Km")}</th><th className="px-4 py-2 text-end font-medium">{t("Cost this month")}</th><th className="w-10" /></tr></thead>
                <tbody className="divide-y divide-line">{vehicles.map((v) => (
                  <tr key={v.id} className={v.active ? "" : "text-muted"}><td className="tabular px-4 py-2 font-medium">{v.plate}</td><td className="px-4 py-2">{v.name} <span className="text-xs text-muted">{v.vehicleType} {v.capacity}</span></td><td className="px-4 py-2">{v.driverName ?? "—"}</td>
                    <td className="px-4 py-2"><Expiry d={v.istimaraExpiry} /></td><td className="px-4 py-2"><Expiry d={v.insuranceExpiry} /></td><td className="px-4 py-2"><Expiry d={v.mvpiExpiry} /></td>
                    <td className="tabular px-4 py-2 text-end">{v.odometer ? Number(v.odometer).toLocaleString("en-US") : "—"}</td><td className="tabular px-4 py-2 text-end">{money(v.costThisMonth)}</td>
                    <td><Button variant="ghost" onClick={() => setEditing(v)} aria-label={t("Edit")}><Pencil className="size-4" aria-hidden /></Button></td></tr>
                ))}</tbody></table></div>
            )}
          </Card>
        </>
      ) : tab === "logs" ? (
        <>
          <Card className="mb-4"><form className="flex flex-wrap items-end gap-3 p-4" onSubmit={async (e) => {
            e.preventDefault(); setError(null);
            try { await api("POST", "/fleet/logs", { ...log, vehicleId: Number(log.vehicleId), amount: Number(log.amount), liters: log.liters ? Number(log.liters) : null, odometer: log.odometer ? Number(log.odometer) : null }); setLog({ ...log, amount: "", liters: "", odometer: "", description: "" }); mutateLogs(); mutate(); }
            catch (err) { setError(errText(err, t("Could not reach the server"))); }
          }}>
            <Field label={t("Vehicle")}><Select required value={log.vehicleId} onChange={(e) => setLog({ ...log, vehicleId: e.target.value })}><option value="">{t("Select…")}</option>{vehicles.filter((v) => v.active).map((v) => <option key={v.id} value={v.id}>{v.plate}</option>)}</Select></Field>
            <Field label={t("Type")}><Select value={log.kind} onChange={(e) => setLog({ ...log, kind: e.target.value })}><option value="FUEL">{t("Fuel")}</option><option value="MAINTENANCE">{t("Maintenance")}</option><option value="OTHER">{t("Other")}</option></Select></Field>
            <Field label={t("Date")}><Input type="date" value={log.date} onChange={(e) => setLog({ ...log, date: e.target.value })} /></Field>
            <Field label={t("Amount (SAR)")}><Input required className="w-28" type="number" min="0" step="0.01" value={log.amount} onChange={(e) => setLog({ ...log, amount: e.target.value })} /></Field>
            {log.kind === "FUEL" && <Field label={t("Liters")} optional><Input className="w-24" type="number" step="any" value={log.liters} onChange={(e) => setLog({ ...log, liters: e.target.value })} /></Field>}
            <Field label={t("Odometer")} optional><Input className="w-28" type="number" step="any" value={log.odometer} onChange={(e) => setLog({ ...log, odometer: e.target.value })} /></Field>
            <Field label={t("Note")} optional><Input value={log.description} onChange={(e) => setLog({ ...log, description: e.target.value })} /></Field>
            <Button type="submit">{t("Save")}</Button>
          </form></Card>
          <Card>{!logs ? <Spinner /> : logs.length === 0 ? <Empty title={t("Nothing logged yet")} /> : (
            <table className="w-full text-sm"><tbody className="divide-y divide-line">{logs.map((l) => (
              <tr key={l.id}><td className="tabular px-4 py-2">{date(l.date)}</td><td className="tabular px-4 py-2 font-medium">{l.plate}</td><td className="px-4 py-2">{t(l.kind.charAt(0) + l.kind.slice(1).toLowerCase())}{l.liters && ` · ${Number(l.liters)} L`}</td>
                <td className="px-4 py-2 text-muted">{l.description}</td><td className="tabular px-4 py-2 text-end">{l.odometer ? `${Number(l.odometer)} km` : ""}</td><td className="tabular px-4 py-2 text-end">{money(l.amount)}</td></tr>
            ))}</tbody></table>
          )}</Card>
        </>
      ) : (
        <>
          <Card className="mb-4"><form className="flex flex-wrap items-end gap-3 p-4" onSubmit={async (e) => {
            e.preventDefault(); setError(null);
            try { await api("POST", "/fleet/trips", { ...trip, vehicleId: Number(trip.vehicleId) }); setTrip({ ...trip, origin: "", destination: "" }); mutateTrips(); }
            catch (err) { setError(errText(err, t("Could not reach the server"))); }
          }}>
            <Field label={t("Vehicle")}><Select required value={trip.vehicleId} onChange={(e) => setTrip({ ...trip, vehicleId: e.target.value })}><option value="">{t("Select…")}</option>{vehicles.filter((v) => v.active).map((v) => <option key={v.id} value={v.id}>{v.plate} · {v.driverName ?? t("no driver")}</option>)}</Select></Field>
            <Field label={t("Date")}><Input type="date" value={trip.tripDate} onChange={(e) => setTrip({ ...trip, tripDate: e.target.value })} /></Field>
            <Field label={t("From")} optional><Input value={trip.origin} onChange={(e) => setTrip({ ...trip, origin: e.target.value })} /></Field>
            <Field label={t("To")} optional><Input value={trip.destination} onChange={(e) => setTrip({ ...trip, destination: e.target.value })} /></Field>
            <Button type="submit"><Plus className="size-4" aria-hidden /> {t("Plan trip")}</Button>
          </form></Card>
          {!trips ? <Spinner /> : trips.length === 0 ? <Card><Empty title={t("No trips yet")} /></Card> : trips.map((x) => <TripCard key={x.id} trip={x} onChange={() => mutateTrips()} />)}
        </>
      )}
    </>
  );
}
