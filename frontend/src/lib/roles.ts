import type { UserRole } from "@/lib/types";

/** Roles the client's admin can hand out; the provider role is the provider's own. */
export const ROLES: { value: UserRole; label: string; hint: string }[] = [
  { value: "ADMIN", label: "Admin", hint: "Everything: all users' records, reports, settings and users" },
  { value: "ACCOUNTANT", label: "Accountant", hint: "Invoices, customers, products, payments and dues" },
  { value: "VIEWER", label: "Viewer", hint: "Can look, cannot change" },
  { value: "BRANCH", label: "Branch", hint: "Only its own invoices, quotations, agreements and cash book" },
];

export function roleLabel(role: UserRole) {
  return role === "OPERATOR" ? "Provider" : ROLES.find((r) => r.value === role)?.label ?? role;
}
