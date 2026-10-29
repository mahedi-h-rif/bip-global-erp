"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { LockKeyhole } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Alert, Button, Field, Input } from "@/components/ui";
import { LanguageSwitcher } from "@/components/language";
import { useT } from "@/lib/i18n";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const t = useT();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await api("POST", "/auth/login", { email, password });
      const next = params.get("next");
      router.replace(next && next.startsWith("/") && !next.startsWith("//") ? next : "/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("Could not reach the server"));
      setLoading(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-4">
      {error && <Alert tone="bad">{error}</Alert>}
      <Field label={t("Email")}>
        <Input type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
      </Field>
      <Field label={t("Password")}>
        <Input type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
      </Field>
      <Button type="submit" loading={loading} className="w-full">
        {t("Sign in")}
      </Button>
    </form>
  );
}

export default function LoginPage() {
  // The company logo, if one is uploaded; the API answers 404 otherwise.
  const [logo, setLogo] = useState(true);
  const t = useT();
  return (
    <main className="relative grid min-h-screen place-items-center bg-[radial-gradient(ellipse_at_top,#e6ecf7,transparent_60%)] px-4">
      <LanguageSwitcher className="absolute end-4 top-4 w-44" />
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center text-center">
          {logo ? (
            // eslint-disable-next-line @next/next/no-img-element
            <img src="/api/v1/organization/logo" alt="" className="mb-4 h-24 w-auto max-w-56 object-contain" onError={() => setLogo(false)} />
          ) : (
            <span className="mb-3 grid size-11 place-items-center rounded-xl bg-brand text-white">
              <LockKeyhole className="size-5" aria-hidden />
            </span>
          )}
          <h1 className="text-lg font-semibold">{t("Invoice Portal")}</h1>
          <p className="text-sm text-muted">{t("ZATCA e-invoicing · staff access only")}</p>
        </div>
        <div className="rounded-xl border border-line bg-white p-6 shadow-sm">
          <Suspense>
            <LoginForm />
          </Suspense>
        </div>
      </div>
    </main>
  );
}
