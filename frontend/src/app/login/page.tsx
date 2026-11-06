"use client";

import { Suspense, useState, type FormEvent } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Eye, EyeOff, LockKeyhole } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { Alert, Button, Field, Input } from "@/components/ui";
import { LanguageSwitcher } from "@/components/language";
import { SoudaCoreCredit } from "@/components/souda-credit";
import { useT } from "@/lib/i18n";

/** Tall enough to tap, and 16px text on phones so iOS doesn't zoom the page when a field is focused. */
const fieldSize = "min-h-11 max-md:text-base";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const t = useT();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
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
      {/* Both fields are plainly needed to sign in, so no "required" markers here. */}
      <Field label={t("Email or user name")} required={false}>
        <Input type="text" dir="ltr" className={`${fieldSize} text-start`} autoComplete="username" autoCapitalize="none" spellCheck={false} required value={email} onChange={(e) => setEmail(e.target.value)} autoFocus />
      </Field>
      <Field label={t("Password")} required={false}>
        <div className="relative">
          <Input type={showPassword ? "text" : "password"} dir="ltr" className={`${fieldSize} pe-11 text-start`} autoComplete="current-password" autoCapitalize="none" spellCheck={false} required value={password} onChange={(e) => setPassword(e.target.value)} />
          <button
            type="button"
            onClick={() => setShowPassword((s) => !s)}
            aria-label={showPassword ? t("Hide password") : t("Show password")}
            title={showPassword ? t("Hide password") : t("Show password")}
            className="absolute inset-y-0 end-0 grid w-11 place-items-center rounded-e-lg text-muted transition hover:text-ink"
          >
            {showPassword ? <EyeOff className="size-4" aria-hidden /> : <Eye className="size-4" aria-hidden />}
          </button>
        </div>
      </Field>
      <Button type="submit" loading={loading} className="min-h-11 w-full">
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
    <div className="flex min-h-dvh flex-col bg-[radial-gradient(ellipse_at_top,color-mix(in_srgb,var(--color-brand)_7%,transparent),transparent_60%)]">
      <main className="flex flex-1 items-center justify-center px-4 py-10 sm:py-16">
        <div className="w-full max-w-sm">
          <div className="mb-6 flex flex-col items-center text-center">
            {logo ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                // A failed load can finish before React attaches onError, so also check once it is mounted.
                ref={(img) => { if (img?.complete && img.naturalWidth === 0) setLogo(false); }}
                src="/api/v1/organization/logo"
                alt=""
                className="mb-4 h-20 w-auto max-w-56 object-contain"
                onError={() => setLogo(false)}
              />
            ) : (
              <span className="mb-4 grid size-12 place-items-center rounded-xl bg-brand text-white shadow-sm">
                <LockKeyhole className="size-5" aria-hidden />
              </span>
            )}
            <h1 className="text-xl font-semibold">{t("Invoice Portal")}</h1>
            <p className="mt-1 text-sm text-muted">{t("ZATCA e-invoicing · staff access only")}</p>
          </div>
          <div className="rounded-xl border border-line bg-white p-5 shadow-sm sm:p-6">
            {/* The same height as the form, so the page doesn't jump when it appears. */}
            <Suspense fallback={<div className="h-60" aria-hidden />}>
              <LoginForm />
            </Suspense>
          </div>
          <div className="mt-4 flex justify-center">
            <LanguageSwitcher className="w-44" />
          </div>
        </div>
      </main>
      <footer className="px-4 pb-6 pt-2">
        <SoudaCoreCredit />
      </footer>
    </div>
  );
}
