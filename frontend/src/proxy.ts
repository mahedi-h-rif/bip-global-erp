import { NextResponse, type NextRequest } from "next/server";

/**
 * Sends visitors without a session cookie to the login page. The API still validates the
 * cookie on every request; this only avoids flashing protected pages.
 */
export function proxy(req: NextRequest) {
  // Any instance's session cookie (UAT and production on one host use different names).
  const hasSession = req.cookies.getAll().some((c) => c.name.startsWith("fatoora_session"));
  const isLogin = req.nextUrl.pathname === "/login";
  if (!hasSession && !isLogin) {
    const url = req.nextUrl.clone();
    url.pathname = "/login";
    url.search = `?next=${encodeURIComponent(req.nextUrl.pathname)}`;
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api/|_next/|favicon.ico|icon|.*\\.(?:png|svg|jpg|ico)$).*)"],
};
