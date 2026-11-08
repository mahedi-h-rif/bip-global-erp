package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.account.AccountReport;
import com.fatoora.api.pdf.LetterheadPdf;
import com.fatoora.api.report.AuditReport.Bucket;
import com.fatoora.api.report.AuditReport.Debtor;
import com.fatoora.api.report.AuditReport.DocStatus;
import com.fatoora.api.report.AuditReport.Finding;
import com.fatoora.api.report.AuditReport.Result;
import com.fatoora.api.report.AuditReport.VatBox;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** The audit report on the company letterhead, every heading in English and Arabic. */
final class AuditReportPdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final Map<AuditReport.Severity, String[]> SEVERITY = Map.of(
            AuditReport.Severity.HIGH, new String[]{"High", "عالية", "#b42318"},
            AuditReport.Severity.MEDIUM, new String[]{"Medium", "متوسطة", "#b26b00"},
            AuditReport.Severity.LOW, new String[]{"Low", "منخفضة", "#1d4ed8"},
            AuditReport.Severity.INFO, new String[]{"Note", "ملاحظة", "#5a6275"});

    private static final Map<String, String[]> STATUS = Map.of(
            "VALID", new String[]{"Valid", "سارية", "#067647"},
            "EXPIRING", new String[]{"Expiring", "قاربت على الانتهاء", "#b26b00"},
            "EXPIRED", new String[]{"Expired", "منتهية", "#b42318"},
            "MISSING", new String[]{"Not on file", "غير محفوظة", "#b42318"});

    private AuditReportPdf() {}

    static String html(Result r) {
        StringBuilder s = new StringBuilder();
        var c = r.company();
        s.append("<h1 style=\"text-align:center\">Business Audit Report</h1>")
                .append("<h1 class=\"rtl\" style=\"text-align:center\" dir=\"rtl\">تقرير التدقيق الإداري للأعمال</h1>")
                .append("<p class=\"muted\" style=\"text-align:center\">Period ").append(date(r.from())).append(" – ").append(date(r.to()))
                .append(" · Currency SAR</p>")
                .append("<p class=\"muted rtl\" dir=\"rtl\" style=\"text-align:center\">الفترة من ").append(date(r.from())).append(" إلى ").append(date(r.to()))
                .append(" · العملة: ريال سعودي</p>");

        s.append(heading("Entity", "بيانات المنشأة")).append("<table style=\"font-size:8.5pt\">")
                .append(kv("Name · الاسم", join(c.nameEn(), c.nameAr())))
                .append(kv("VAT number · الرقم الضريبي", c.vatNumber()))
                .append(kv("CR number · السجل التجاري", c.crNumber()))
                .append(kv("National address · العنوان الوطني", c.address()))
                .append(kv("Prepared by · أعدّه", r.preparedBy()))
                .append(kv("Generated · تاريخ الإعداد", STAMP.format(r.generatedAt().atZone(InvoiceApiApplication.KSA))))
                .append("</table>");

        // Score
        var sc = r.score();
        String color = sc.score() >= 85 ? "#067647" : sc.score() >= 60 ? "#b26b00" : "#b42318";
        s.append("<table style=\"margin:10px 0\"><tr>")
                .append("<td style=\"width:28%; border:2px solid ").append(color).append("; text-align:center; padding:8px\">")
                .append("<div style=\"font-size:26pt; font-weight:700; color:").append(color).append("\">").append(sc.score()).append("/100</div>")
                .append("<div style=\"font-weight:700\">").append(esc(sc.rating())).append(" · <span dir=\"rtl\">").append(esc(sc.ratingAr())).append("</span></div></td>")
                .append("<td style=\"padding-left:12px; vertical-align:middle\">")
                .append(kpiRow(new String[][]{
                        {"High risk · عالية", String.valueOf(sc.high())}, {"Medium · متوسطة", String.valueOf(sc.medium())},
                        {"Low · منخفضة", String.valueOf(sc.low())}, {"Output VAT · ضريبة المخرجات", LetterheadPdf.money(r.vat().total().vat())},
                        {"Owed by customers · مستحقات العملاء", LetterheadPdf.money(r.receivables().totalDue())}}))
                .append("</td></tr></table>");

        // 1. Findings
        s.append(heading("1. Findings and recommendations", "١. الملاحظات والتوصيات"));
        s.append("<table style=\"font-size:8.5pt\">").append(head(new String[]{"#", "Risk · الخطورة", "Finding · الملاحظة", "Recommendation · التوصية"}));
        int n = 1;
        for (Finding f : r.findings()) {
            String[] sv = SEVERITY.get(f.severity());
            s.append("<tr style=\"page-break-inside:avoid\">").append(td(String.valueOf(n++)))
                    .append(td("<b style=\"color:" + sv[2] + "\">" + sv[0] + "</b><br/><span dir=\"rtl\">" + sv[1] + "</span><br/><span class=\"muted\">" + esc(f.area()) + "</span>"))
                    .append(td(esc(AuditReport.fill(f.text(), f.vars())) + "<div class=\"rtl\" dir=\"rtl\">" + esc(AuditReport.fill(f.textAr(), f.vars())) + "</div>"))
                    .append(td(esc(AuditReport.fill(f.action(), f.vars())) + "<div class=\"rtl\" dir=\"rtl\">" + esc(AuditReport.fill(f.actionAr(), f.vars())) + "</div>"))
                    .append("</tr>");
        }
        s.append("</table>");

        // 2. VAT return
        s.append(heading("2. VAT return summary (ZATCA, sales)", "٢. ملخص الإقرار الضريبي (المبيعات)"));
        s.append("<table style=\"font-size:8.5pt; page-break-inside:avoid\">")
                .append(head(new String[]{"Box · البند", "Description · الوصف", "Amount (SAR) · المبلغ", "Adjustment · التعديلات", "VAT · الضريبة"}, 2));
        for (VatBox b : r.vat().boxes()) s.append(vatRow(b, false));
        s.append(vatRow(r.vat().total(), true)).append("</table>");
        s.append("<p class=\"muted\" style=\"font-size:8pt\">Amounts exclude VAT. Adjustments are credit notes (−) and debit notes (+) issued in the period. ");
        if (r.vat().outOfScope().signum() != 0)
            s.append("Out-of-scope supplies, not part of the return: SAR ").append(LetterheadPdf.money(r.vat().outOfScope())).append(". ");
        s.append("Purchases (boxes 7–13) are not recorded in this system. Return due by ").append(date(r.vat().filingDeadline())).append(".</p>");

        // 3. E-invoicing
        var e = r.einvoicing();
        s.append(heading("3. E-invoicing compliance (Fatoora Phase 2)", "٣. الالتزام بالفوترة الإلكترونية (فاتورة – المرحلة الثانية)"));
        s.append("<table style=\"font-size:8.5pt; page-break-inside:avoid\">").append(head(new String[]{"Check · البند", "Result · النتيجة"}, 1))
                .append(kv("Invoices / credit notes / debit notes · فواتير / إشعارات دائن / مدين", e.invoices() + " / " + e.creditNotes() + " / " + e.debitNotes()))
                .append(kv("Tax (B2B) / simplified (B2C) · ضريبية / مبسطة", e.standard() + " / " + e.simplified()))
                .append(kv("Waiting for ZATCA · بانتظار الهيئة", String.valueOf(e.pending())))
                .append(kv("Rejected by ZATCA · مرفوضة", String.valueOf(e.rejected())))
                .append(kv("Simplified reported after 24 h · مبسطة أُبلغ عنها بعد 24 ساعة", String.valueOf(e.lateReports())))
                .append(kv("Not accepted after 24 h · غير مقبولة بعد 24 ساعة", String.valueOf(e.overdueUnreported())))
                .append(kv("Tax invoices without buyer VAT/ID · فواتير ضريبية بلا رقم ضريبي للمشتري", String.valueOf(e.standardWithoutBuyerId())))
                .append(kv("Counter (ICV) gaps · فجوات العداد", String.valueOf(e.chainProblems())))
                .append(kv("Drafts older than 7 days · مسودات أقدم من 7 أيام", String.valueOf(e.oldDrafts())))
                .append(kv("Credit notes as % of sales · نسبة إشعارات الدائن من المبيعات", e.creditNotePct().toPlainString() + "%"))
                .append("</table>");

        // 4. Receivables
        var rec = r.receivables();
        s.append(heading("4. Sales and receivables", "٤. المبيعات والذمم المدينة"));
        s.append(kpiRow(new String[][]{
                {"Sales incl. VAT · المبيعات", LetterheadPdf.money(rec.sales())}, {"Collected · المحصّل", LetterheadPdf.money(rec.collected())},
                {"Collection rate · نسبة التحصيل", rec.collectionPct().toPlainString() + "%"}, {"Owed now · المستحق حالياً", LetterheadPdf.money(rec.totalDue())}}));
        s.append("<table style=\"font-size:8.5pt; margin-top:6px; page-break-inside:avoid\">")
                .append(head(new String[]{"Age · العمر", "Invoices · الفواتير", "Amount (SAR) · المبلغ"}, 1));
        for (Bucket b : rec.aging())
            s.append("<tr>").append(td(esc(b.label()) + " · <span dir=\"rtl\">" + esc(b.labelAr()) + "</span>"))
                    .append(tdr(String.valueOf(b.invoices()))).append(tdr(LetterheadPdf.money(b.amount()))).append("</tr>");
        s.append("</table>");
        if (!rec.topDebtors().isEmpty()) {
            s.append("<table style=\"font-size:8.5pt; margin-top:6px; page-break-inside:avoid\">")
                    .append(head(new String[]{"Largest debtors · أكبر المدينين", "Invoices · الفواتير", "Oldest (days) · الأقدم", "Owed · المستحق"}, 1));
            for (Debtor d : rec.topDebtors())
                s.append("<tr>").append(td(esc(d.name()))).append(tdr(String.valueOf(d.invoices())))
                        .append(tdr(String.valueOf(d.oldestDays()))).append(tdr(LetterheadPdf.money(d.due()))).append("</tr>");
            s.append("</table>");
        }

        // 5. Cash
        var cash = r.cash();
        s.append(heading("5. Cash book and partners", "٥. الصندوق والشركاء"));
        s.append(kpiRow(new String[][]{
                {"Received · المقبوضات", LetterheadPdf.money(cash.received())}, {"Expenses · المصروفات", LetterheadPdf.money(cash.expenses())},
                {"Net cash profit · صافي الربح النقدي", signed(cash.net())}, {"Partner drawings · مسحوبات الشركاء", LetterheadPdf.money(cash.drawings())},
                {"Kept in company · المحتفظ به", signed(cash.retained())}}));
        if (!cash.categories().isEmpty()) {
            s.append("<table style=\"font-size:8.5pt; margin-top:6px; page-break-inside:avoid\">").append(head(new String[]{"Expense category · بند المصروف", "Amount · المبلغ"}, 1));
            for (AccountReport.Category k : cash.categories())
                s.append("<tr>").append(td(esc(AccountReport.categoryName(k.category())))).append(tdr(LetterheadPdf.money(k.amount()))).append("</tr>");
            s.append("</table>");
        }

        // 6. Documents and workforce
        s.append(heading("6. Statutory documents and workforce", "٦. المستندات النظامية والعمالة"));
        s.append("<table style=\"font-size:8.5pt; page-break-inside:avoid\">")
                .append(head(new String[]{"Document · المستند", "Number · الرقم", "Expiry · الانتهاء", "Status · الحالة"}, 9));
        for (DocStatus d : r.documents()) {
            String[] st = STATUS.get(d.status());
            s.append("<tr>").append(td(esc(d.label()) + "<div class=\"rtl\" dir=\"rtl\">" + esc(d.labelAr()) + "</div>"))
                    .append(td(esc(d.number() == null ? "–" : d.number()))).append(td(d.expiry() == null ? "–" : date(d.expiry())))
                    .append(td("<b style=\"color:" + st[2] + "\">" + st[0] + "</b> · <span dir=\"rtl\">" + st[1] + "</span>")).append("</tr>");
        }
        s.append("</table>");
        var w = r.workforce();
        s.append(kpiRow(new String[][]{
                {"Active workers · العمال", String.valueOf(w.active())}, {"Iqama valid · إقامة سارية", String.valueOf(w.iqamaValid())},
                {"Expiring · قاربت", String.valueOf(w.iqamaExpiring())}, {"Expired · منتهية", String.valueOf(w.iqamaExpired())},
                {"Not on file · غير محفوظة", String.valueOf(w.iqamaMissing())}}));

        // 7. Controls
        var ctl = r.controls();
        s.append(heading("7. Internal control (activity log)", "٧. الرقابة الداخلية (سجل النشاط)"));
        s.append(kpiRow(new String[][]{
                {"Recorded actions · العمليات المسجلة", String.valueOf(ctl.events())}, {"Users · المستخدمون", String.valueOf(ctl.users())},
                {"Deletions · عمليات الحذف", String.valueOf(ctl.deletions())}, {"Payment edits · تعديل المدفوعات", String.valueOf(ctl.paymentEdits())},
                {"Failed sign-ins · دخول فاشل", String.valueOf(ctl.failedLogins())}}));

        // 8. Calendar
        s.append(heading("8. Compliance calendar", "٨. مواعيد الالتزام القادمة"));
        s.append("<table style=\"font-size:8.5pt; page-break-inside:avoid\">").append(head(new String[]{"Obligation · الالتزام", "Due by · قبل"}, 1));
        for (var d : r.calendar())
            s.append("<tr>").append(td(esc(d.label()) + "<div class=\"rtl\" dir=\"rtl\">" + esc(d.labelAr()) + "</div>")).append(tdr(date(d.date()))).append("</tr>");
        s.append("</table>");

        // Basis and sign-off
        s.append("<div style=\"page-break-inside:avoid\">").append(heading("Basis of preparation", "أساس الإعداد"))
                .append("<p style=\"font-size:8pt\">Checked against the ZATCA VAT Implementing Regulations and E-Invoicing (Fatoora) requirements, "
                        + "IFRS as endorsed by SOCPA (IFRS for SMEs where it applies), and Saudi residency and labour rules for worker documents. "
                        + "This is an internal management audit prepared from the records in this system. It is not an independent auditor's "
                        + "opinion: statutory financial statements must be audited by a SOCPA-licensed auditor where the Companies Law requires it.</p>")
                .append("<p class=\"rtl\" dir=\"rtl\" style=\"font-size:8pt\">تمت المراجعة وفق اللائحة التنفيذية لنظام ضريبة القيمة المضافة ومتطلبات الفوترة الإلكترونية (فاتورة)، "
                        + "والمعايير الدولية للتقرير المالي المعتمدة من الهيئة السعودية للمراجعين والمحاسبين، وأنظمة الإقامة والعمل في المملكة. "
                        + "هذا تقرير تدقيق إداري داخلي معدّ من سجلات هذا النظام، وليس رأي مراجع مستقل؛ ويجب أن تُراجع القوائم المالية النظامية من مراجع حسابات مرخّص عند اشتراط نظام الشركات ذلك.</p>")
                .append(signOff(r.preparedBy()))
                .append("</div>");
        return s.toString();
    }

    private static String signOff(String preparedBy) {
        String cell = "<td style=\"width:33%%; vertical-align:bottom; padding:0 8px\"><div style=\"height:56px\"></div>"
                + "<div style=\"border-top:1px solid #1b2233; padding-top:3px; font-weight:700\">%s</div><div class=\"muted\">%s</div></td>";
        return "<table style=\"margin-top:14px\"><tr>"
                + cell.formatted("Prepared by · أعدّه", esc(preparedBy == null ? "" : preparedBy))
                + cell.formatted("Reviewed by · راجعه", "")
                + cell.formatted("Approved by · اعتمده", "")
                + "</tr></table>";
    }

    private static String vatRow(VatBox b, boolean total) {
        return "<tr" + (total ? " style=\"font-weight:700; background:#f6f8fb\"" : "") + ">" + td(b.box())
                + td(esc(b.label()) + "<div class=\"rtl\" dir=\"rtl\">" + esc(b.labelAr()) + "</div>")
                + tdr(LetterheadPdf.money(b.amount())) + tdr(signed(b.adjustment())) + tdr(LetterheadPdf.money(b.vat())) + "</tr>";
    }

    private static String heading(String en, String ar) {
        return "<table style=\"margin-top:12px; border-bottom:1.5px solid #c9d1df\"><tr><td><h2 style=\"margin:0\">" + esc(en) + "</h2></td>"
                + "<td class=\"rtl\" dir=\"rtl\"><h2 style=\"margin:0\">" + esc(ar) + "</h2></td></tr></table>";
    }

    private static String kpiRow(String[][] items) {
        StringBuilder s = new StringBuilder("<table><tr>");
        for (String[] i : items)
            s.append("<td style=\"padding:3px\"><div style=\"border:1px solid #e3e7ef; background:#f6f8fb; padding:5px 7px\">")
                    .append("<div class=\"muted\" style=\"font-size:7pt\">").append(bi(i[0])).append("</div>")
                    .append("<div style=\"font-size:10.5pt; font-weight:700\">").append(i[1]).append("</div></div></td>");
        return s.append("</tr></table>").toString();
    }

    private static String head(String[] cols) {
        return head(cols, cols.length);
    }

    /** Header row; columns from {@code firstNumeric} on are right-aligned. */
    private static String head(String[] cols, int firstNumeric) {
        StringBuilder h = new StringBuilder("<tr>");
        for (int i = 0; i < cols.length; i++)
            h.append("<th style=\"background:#e9edf4; padding:4px 6px; text-align:").append(i >= firstNumeric ? "right" : "left")
                    .append("\">").append(bi(cols[i])).append("</th>");
        return h.append("</tr>").toString();
    }

    /** "English · عربي": the English, and the Arabic on its own right-to-left line so digits stay in order. */
    private static String bi(String label) {
        int dot = label.indexOf(" · ");
        if (dot < 0) return esc(label);
        return esc(label.substring(0, dot)) + "<div class=\"rtl\" dir=\"rtl\">" + esc(label.substring(dot + 3)) + "</div>";
    }

    private static String kv(String label, String value) {
        return "<tr>" + td(bi(label)) + tdr("<b>" + LetterheadPdf.text(value == null || value.isBlank() ? "–" : value) + "</b>") + "</tr>";
    }

    private static String td(String html) {
        return "<td style=\"padding:3px 6px; border-bottom:1px solid #eef1f6; vertical-align:top\">" + html + "</td>";
    }

    private static String tdr(String html) {
        return "<td style=\"padding:3px 6px; border-bottom:1px solid #eef1f6; vertical-align:top; text-align:right\">" + html + "</td>";
    }

    private static String signed(BigDecimal v) {
        return v.signum() < 0 ? "<span style=\"color:#b42318\">(" + LetterheadPdf.money(v.negate()) + ")</span>" : LetterheadPdf.money(v);
    }

    private static String join(String a, String b) {
        return a == null || a.isBlank() ? b : b == null || b.isBlank() ? a : a + "\n" + b;
    }

    private static String date(LocalDate d) {
        return d == null ? "" : DATE.format(d);
    }

    private static String esc(String s) {
        return LetterheadPdf.esc(s == null ? "" : s);
    }
}
