package com.fatoora.api.report;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.account.AccountReport;
import com.fatoora.api.document.CompanyDocument;
import com.fatoora.api.document.ExpiryRule;
import com.fatoora.api.invoice.InvoiceAuditRow;
import com.fatoora.api.invoice.InvoiceFigures;
import com.fatoora.api.invoice.InvoiceStatus;
import com.fatoora.api.payment.BalanceCalculator.Balance;
import com.fatoora.zatca.model.DocumentType;
import com.fatoora.zatca.model.InvoiceKind;
import com.fatoora.zatca.model.VatCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The business audit of one period, checked against Saudi requirements: the ZATCA VAT return boxes,
 * e-invoicing (Fatoora) rules, receivables, the cash book and partner drawings, statutory documents
 * (CR, VAT certificate, Iqamas) and the activity log. Pure function, unit tested.
 *
 * <p>It is a management audit from the system's own records, not an independent auditor's opinion.
 * Every finding has an English and an Arabic text; {placeholders} are filled from {@code vars}, so the
 * portal can also translate the English text into Bangla and Hindi.
 */
public final class AuditReport {

    public enum Severity { HIGH, MEDIUM, LOW, INFO }

    /** Deductions from 100 for each finding. */
    private static final Map<Severity, Integer> PENALTY = Map.of(Severity.HIGH, 15, Severity.MEDIUM, 7, Severity.LOW, 3, Severity.INFO, 0);

    /** ZATCA's limit for reporting a simplified invoice after it is issued. */
    static final Duration REPORTING_LIMIT = Duration.ofHours(24);

    // --- inputs -----------------------------------------------------------------------------------

    /** The company as registered; {@code missing} lists the profile fields still empty. */
    public record Company(String nameEn, String nameAr, String vatNumber, String crNumber, String address, boolean live,
                          List<String> missing) {}

    public record Party(Long id, String name, boolean hasTaxId, String countryCode) {}

    public record LineNet(Long invoiceId, VatCategory category, BigDecimal net) {}

    public record ChainLink(Long egsUnitId, Long icv) {}

    /** A company document (workerId null) or a worker's document. */
    public record FileOnRecord(CompanyDocument.Type type, Long workerId, String number, LocalDate expiry) {}

    public record Staff(Long id, String name, boolean active) {}

    /** The cash book of the period: money received, expenses (worker payments included), partner drawings. */
    public record Cash(BigDecimal received, BigDecimal expenses, BigDecimal drawings, List<AccountReport.Category> categories) {}

    public record Activity(Map<String, Long> actions, long users) {}

    public record Inputs(Company company, LocalDate from, LocalDate to, LocalDate today, Instant now, String preparedBy,
                         List<InvoiceAuditRow> docs, List<LineNet> lines, List<ChainLink> chain,
                         List<InvoiceFigures> allIssued, Map<Long, Balance> balances, Map<Long, Party> customers,
                         Cash cash, List<FileOnRecord> files, List<Staff> workers, Activity activity) {}

    // --- result -----------------------------------------------------------------------------------

    public record Finding(String code, Severity severity, String area, String text, String textAr, String action,
                          String actionAr, Map<String, String> vars) {}

    public record Score(int score, String rating, String ratingAr, long high, long medium, long low) {}

    /** One line of the ZATCA VAT return: sales (excl. VAT), adjustments from credit and debit notes, VAT. */
    public record VatBox(String box, String label, String labelAr, BigDecimal amount, BigDecimal adjustment, BigDecimal vat) {}

    public record VatReturn(List<VatBox> boxes, VatBox total, BigDecimal outOfScope, LocalDate filingDeadline) {}

    public record Einvoicing(long invoices, long creditNotes, long debitNotes, long standard, long simplified,
                             Map<String, Long> byStatus, long pending, long rejected, long lateReports, long overdueUnreported,
                             long standardWithoutBuyerId, long chainProblems, long oldDrafts, BigDecimal creditNotePct) {}

    public record Bucket(String label, String labelAr, BigDecimal amount, long invoices) {}

    public record Debtor(String name, BigDecimal due, long invoices, long oldestDays) {}

    public record Receivables(BigDecimal sales, BigDecimal collected, BigDecimal collectionPct, BigDecimal totalDue,
                              List<Bucket> aging, List<Debtor> topDebtors, String topCustomer, BigDecimal topCustomerPct) {}

    public record CashSummary(BigDecimal received, BigDecimal expenses, BigDecimal net, BigDecimal drawings, BigDecimal retained,
                              List<AccountReport.Category> categories) {}

    /** status: VALID, EXPIRING (within 60 days), EXPIRED or MISSING. */
    public record DocStatus(String type, String label, String labelAr, String number, LocalDate expiry, String status, Long daysLeft) {}

    public record Workforce(long active, long iqamaValid, long iqamaExpiring, long iqamaExpired, long iqamaMissing) {}

    public record Controls(long events, long users, long deletions, long paymentEdits, long failedLogins) {}

    public record Deadline(String label, String labelAr, LocalDate date) {}

    public record Result(Company company, LocalDate from, LocalDate to, Instant generatedAt, String preparedBy, Score score,
                         List<Finding> findings, VatReturn vat, Einvoicing einvoicing, Receivables receivables, CashSummary cash,
                         List<DocStatus> documents, Workforce workforce, Controls controls, List<Deadline> calendar) {}

    private AuditReport() {}

    public static Result build(Inputs in) {
        List<Finding> findings = new ArrayList<>();
        companyFindings(in.company(), findings);
        VatReturn vat = vatReturn(in, findings);
        Einvoicing einv = einvoicing(in, findings);
        Receivables rec = receivables(in, findings);
        CashSummary cash = cash(in.cash(), findings);
        List<DocStatus> docs = documents(in, findings);
        Workforce staff = workforce(in, findings);
        Controls controls = controls(in.activity(), findings);
        findings.sort(Comparator.comparing(Finding::severity));
        return new Result(in.company(), in.from(), in.to(), in.now(), in.preparedBy(), score(findings), findings, vat, einv, rec,
                cash, docs, staff, controls, calendar(in, docs));
    }

    // --- company ----------------------------------------------------------------------------------

    private static final Map<String, String> FIELD_AR = Map.of("Legal name", "الاسم النظامي", "VAT number", "الرقم الضريبي",
            "CR number", "رقم السجل التجاري", "national address", "العنوان الوطني");

    private static void companyFindings(Company c, List<Finding> out) {
        if (!c.live())
            out.add(finding("ZATCA_NOT_LIVE", Severity.HIGH, "E-invoicing",
                    "E-invoicing is not connected to ZATCA production, so invoices are not legally cleared or reported.",
                    "الفوترة الإلكترونية غير مرتبطة ببيئة الإنتاج لدى هيئة الزكاة والضريبة والجمارك، لذا لا تُعتمد الفواتير ولا يُبلَّغ عنها رسمياً.",
                    "Ask your e-invoicing provider to complete production onboarding.",
                    "اطلب من مزود خدمة الفوترة الإلكترونية إكمال الربط مع بيئة الإنتاج.", Map.of()));
        if (!c.missing().isEmpty())
            out.add(finding("COMPANY_DETAILS", Severity.MEDIUM, "Company",
                    "The company profile is missing: {fields}.",
                    "ملف الشركة ينقصه: {fieldsAr}.",
                    "Complete the company details under Settings; they are printed on every tax invoice.",
                    "أكمل بيانات الشركة من الإعدادات؛ فهي تُطبع على كل فاتورة ضريبية.",
                    Map.of("fields", String.join(", ", c.missing()),
                            "fieldsAr", c.missing().stream().map(f -> FIELD_AR.getOrDefault(f, f)).collect(Collectors.joining("، ")))));
    }

    // --- VAT return -------------------------------------------------------------------------------

    /**
     * The sales side of the ZATCA VAT return. Each document's net is split by VAT category in the
     * proportion of its lines, scaled to the document's own total so rounding matches the invoice.
     * Zero-rated sales to a customer outside Saudi Arabia are exports.
     */
    static VatReturn vatReturn(Inputs in, List<Finding> out) {
        Map<Long, Map<VatCategory, BigDecimal>> byDoc = new HashMap<>();
        for (LineNet l : in.lines()) byDoc.computeIfAbsent(l.invoiceId(), k -> new EnumMap<>(VatCategory.class)).merge(l.category(), nz(l.net()), BigDecimal::add);

        BigDecimal[] amount = zeros(5), adjust = zeros(5), vat = zeros(5);
        BigDecimal outOfScope = BigDecimal.ZERO;
        for (InvoiceAuditRow d : issuedInPeriod(in)) {
            int sign = d.type() == DocumentType.CREDIT_NOTE ? -1 : 1;
            boolean note = d.type() != DocumentType.INVOICE;
            Map<VatCategory, BigDecimal> parts = byDoc.getOrDefault(d.id(), Map.of(VatCategory.S, nz(d.totalExclVat())));
            BigDecimal linesTotal = parts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            Party buyer = d.customerId() == null ? null : in.customers().get(d.customerId());
            boolean export = buyer != null && buyer.countryCode() != null && !"SA".equalsIgnoreCase(buyer.countryCode());
            for (var e : parts.entrySet()) {
                BigDecimal net = linesTotal.signum() == 0 ? BigDecimal.ZERO
                        : nz(d.totalExclVat()).multiply(e.getValue()).divide(linesTotal, 2, RoundingMode.HALF_UP);
                BigDecimal signed = sign < 0 ? net.negate() : net;
                if (e.getKey() == VatCategory.O) {
                    outOfScope = outOfScope.add(signed);
                    continue;
                }
                int box = switch (e.getKey()) {
                    case S -> 0;
                    case Z -> export ? 2 : 1;
                    default -> 3;
                };
                if (note) adjust[box] = adjust[box].add(signed);
                else amount[box] = amount[box].add(signed);
            }
            BigDecimal v = sign < 0 ? nz(d.vatTotal()).negate() : nz(d.vatTotal());
            vat[0] = vat[0].add(v);
        }
        String[][] labels = {
                {"1", "Standard rated sales (15%)", "المبيعات الخاضعة للنسبة الأساسية 15%"},
                {"3", "Zero rated domestic sales", "المبيعات المحلية الخاضعة للنسبة الصفرية"},
                {"4", "Exports", "الصادرات"},
                {"5", "Exempt sales", "المبيعات المعفاة"},
        };
        List<VatBox> boxes = new ArrayList<>();
        for (int i = 0; i < labels.length; i++)
            boxes.add(new VatBox(labels[i][0], labels[i][1], labels[i][2], amount[i], adjust[i], vat[i]));
        BigDecimal totalAmount = sum(amount), totalAdjust = sum(adjust), totalVat = sum(vat);
        VatBox total = new VatBox("6", "Total sales", "إجمالي المبيعات", totalAmount, totalAdjust, totalVat);
        LocalDate deadline = YearMonth.from(in.to()).plusMonths(1).atEndOfMonth();

        out.add(finding("VAT_RETURN_DUE", Severity.INFO, "VAT",
                "Output VAT for the period is SAR {vat}. The VAT return is due by {date}.",
                "ضريبة المخرجات للفترة {vat} ريال. يجب تقديم الإقرار الضريبي قبل {date}.",
                "File the return on the ZATCA portal and pay the net VAT by the same date.",
                "قدّم الإقرار عبر بوابة الهيئة وسدّد صافي الضريبة في الموعد نفسه.",
                Map.of("vat", money(totalVat), "date", dmy(deadline))));
        out.add(finding("INPUT_VAT_NOT_RECORDED", Severity.INFO, "VAT",
                "Purchases and input VAT are not recorded in this system.",
                "المشتريات وضريبة المدخلات غير مسجلة في هذا النظام.",
                "Complete the purchases section of the VAT return (boxes 7 to 13) from your purchase tax invoices, and keep them for at least 6 years.",
                "أكمل قسم المشتريات في الإقرار (البنود 7 إلى 13) من فواتير الشراء الضريبية، واحتفظ بها 6 سنوات على الأقل.",
                Map.of()));
        return new VatReturn(boxes, total, outOfScope, deadline);
    }

    // --- e-invoicing ------------------------------------------------------------------------------

    static Einvoicing einvoicing(Inputs in, List<Finding> out) {
        List<InvoiceAuditRow> issued = in.docs().stream()
                .filter(d -> d.issuedAt() != null && d.status() != InvoiceStatus.DRAFT && inPeriod(in, d.issuedAt())).toList();
        Map<String, Long> byStatus = new TreeMap<>(issued.stream().collect(Collectors.groupingBy(d -> d.status().name(), Collectors.counting())));
        long invoices = issued.stream().filter(d -> d.type() == DocumentType.INVOICE).count();
        long credit = issued.stream().filter(d -> d.type() == DocumentType.CREDIT_NOTE).count();
        long debit = issued.stream().filter(d -> d.type() == DocumentType.DEBIT_NOTE).count();
        long standard = issued.stream().filter(d -> d.kind() == InvoiceKind.STANDARD).count();
        long pending = issued.stream().filter(d -> d.status() == InvoiceStatus.SIGNED).count();
        long rejected = issued.stream().filter(d -> d.status() == InvoiceStatus.REJECTED).count();
        long late = issued.stream().filter(d -> d.kind() == InvoiceKind.SIMPLIFIED && d.status().accepted() && d.submittedAt() != null
                && Duration.between(d.issuedAt(), d.submittedAt()).compareTo(REPORTING_LIMIT) > 0).count();
        long overdue = issued.stream().filter(d -> d.status() == InvoiceStatus.SIGNED
                && Duration.between(d.issuedAt(), in.now()).compareTo(REPORTING_LIMIT) > 0).count();
        long noBuyerId = issued.stream().filter(d -> d.kind() == InvoiceKind.STANDARD && d.type() == DocumentType.INVOICE && d.status().billable()
                && (d.customerId() == null || !in.customers().containsKey(d.customerId()) || !in.customers().get(d.customerId()).hasTaxId())).count();
        long drafts = in.docs().stream().filter(d -> d.status() == InvoiceStatus.DRAFT && d.createdAt() != null
                && ChronoUnit.DAYS.between(d.createdAt(), in.now()) > 7).count();
        long chain = chainProblems(in.chain());

        BigDecimal sales = issued.stream().filter(d -> d.type() == DocumentType.INVOICE && d.status().billable())
                .map(d -> nz(d.totalExclVat())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = issued.stream().filter(d -> d.type() == DocumentType.CREDIT_NOTE && d.status().billable())
                .map(d -> nz(d.totalExclVat())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal creditPct = pct(credits, sales);

        if (late > 0)
            out.add(finding("LATE_REPORTING", Severity.HIGH, "E-invoicing",
                    "{n} simplified invoices were reported to ZATCA more than 24 hours after they were issued.",
                    "تم الإبلاغ عن {n} فاتورة مبسطة لهيئة الزكاة والضريبة والجمارك بعد أكثر من 24 ساعة من إصدارها.",
                    "Report simplified invoices within 24 hours: use \"send now\", or make sure queued invoices go out the same day.",
                    "أبلغ عن الفواتير المبسطة خلال 24 ساعة: استخدم \"إرسال الآن\" أو تأكد من إرسال الفواتير المؤجلة في اليوم نفسه.",
                    Map.of("n", String.valueOf(late))));
        if (overdue > 0)
            out.add(finding("UNREPORTED_OVERDUE", Severity.HIGH, "E-invoicing",
                    "{n} documents are signed but not yet accepted by ZATCA, more than 24 hours after issue.",
                    "{n} مستند موقّع ولم تقبله الهيئة بعد، رغم مرور أكثر من 24 ساعة على إصداره.",
                    "Open each one under Invoices and send it to ZATCA; contact your provider if sending keeps failing.",
                    "افتح كل مستند من قائمة الفواتير وأرسله للهيئة؛ وتواصل مع مزود الخدمة إذا استمر الفشل.",
                    Map.of("n", String.valueOf(overdue))));
        if (rejected > 0)
            out.add(finding("REJECTED", Severity.HIGH, "E-invoicing",
                    "{n} documents were rejected by ZATCA in the period.",
                    "رفضت الهيئة {n} مستند خلال الفترة.",
                    "Use \"Correct & reissue\" on each rejected document so the sale is invoiced correctly.",
                    "استخدم \"تصحيح وإعادة الإصدار\" لكل مستند مرفوض حتى تُفوتر عملية البيع بشكل صحيح.",
                    Map.of("n", String.valueOf(rejected))));
        if (chain > 0)
            out.add(finding("CHAIN_GAP", Severity.HIGH, "E-invoicing",
                    "The invoice counter (ICV) has {n} gaps or repeated numbers.",
                    "عداد الفواتير (ICV) فيه {n} فجوة أو رقم مكرر.",
                    "Contact your e-invoicing provider; every document must follow the previous one without gaps.",
                    "تواصل مع مزود الخدمة؛ يجب أن يتبع كل مستند ما قبله دون فجوات.",
                    Map.of("n", String.valueOf(chain))));
        if (noBuyerId > 0)
            out.add(finding("STANDARD_NO_BUYER_ID", Severity.MEDIUM, "E-invoicing",
                    "{n} tax (B2B) invoices are for customers with no VAT number or other ID on file.",
                    "{n} فاتورة ضريبية لعملاء ليس لديهم رقم ضريبي أو رقم تعريف آخر مسجل.",
                    "Add the buyer's VAT number (or CR number) to the customer; a tax invoice must show it.",
                    "أضف الرقم الضريبي للمشتري (أو رقم السجل التجاري) في بيانات العميل؛ إذ يجب أن يظهر في الفاتورة الضريبية.",
                    Map.of("n", String.valueOf(noBuyerId))));
        if (creditPct.compareTo(BigDecimal.TEN) > 0)
            out.add(finding("HIGH_CREDIT_NOTES", Severity.MEDIUM, "E-invoicing",
                    "Credit notes are {pct}% of sales in the period.",
                    "إشعارات الدائن تمثل {pct}% من المبيعات خلال الفترة.",
                    "Review why goods are returned or prices corrected, and who approves credit notes.",
                    "راجع أسباب المرتجعات أو تصحيح الأسعار، ومن يعتمد إشعارات الدائن.",
                    Map.of("pct", creditPct.toPlainString())));
        if (drafts > 0)
            out.add(finding("OLD_DRAFTS", Severity.LOW, "E-invoicing",
                    "{n} invoice drafts are older than 7 days.",
                    "{n} مسودة فاتورة مضى عليها أكثر من 7 أيام.",
                    "Issue them or delete them; a draft has no legal number and is not a valid invoice.",
                    "أصدرها أو احذفها؛ فالمسودة ليس لها رقم نظامي وليست فاتورة صالحة.",
                    Map.of("n", String.valueOf(drafts))));
        return new Einvoicing(invoices, credit, debit, standard, issued.size() - standard, byStatus, pending, rejected, late, overdue,
                noBuyerId, chain, drafts, creditPct);
    }

    /** Gaps and repeats in each EGS unit's ICV sequence, from its first counter to its last. */
    static long chainProblems(List<ChainLink> chain) {
        Map<Long, List<Long>> byUnit = new HashMap<>();
        for (ChainLink c : chain) byUnit.computeIfAbsent(c.egsUnitId(), k -> new ArrayList<>()).add(c.icv());
        long problems = 0;
        for (List<Long> icvs : byUnit.values()) {
            icvs.sort(null);
            for (int i = 1; i < icvs.size(); i++) {
                long step = icvs.get(i) - icvs.get(i - 1);
                if (step != 1) problems++;
            }
        }
        return problems;
    }

    // --- receivables ------------------------------------------------------------------------------

    static Receivables receivables(Inputs in, List<Finding> out) {
        BigDecimal sales = BigDecimal.ZERO, collected = BigDecimal.ZERO;
        Map<Long, BigDecimal> byCustomer = new HashMap<>();
        for (InvoiceFigures f : in.allIssued()) {
            Balance b = in.balances().get(f.id());
            if (f.type() != DocumentType.INVOICE || b == null || !inPeriod(in, f.issuedAt())) continue;
            sales = sales.add(b.adjusted());
            collected = collected.add(b.paid().min(b.adjusted()));
            if (f.customerId() != null) byCustomer.merge(f.customerId(), b.adjusted(), BigDecimal::add);
        }

        String[][] names = {
                {"Not yet due", "لم يحن موعدها"}, {"1–30 days", "1–30 يوماً"}, {"31–60 days", "31–60 يوماً"},
                {"61–90 days", "61–90 يوماً"}, {"91–180 days", "91–180 يوماً"}, {"Over 180 days", "أكثر من 180 يوماً"}};
        BigDecimal[] amounts = zeros(names.length);
        long[] counts = new long[names.length];
        Map<String, Debtor> debtors = new LinkedHashMap<>();
        BigDecimal totalDue = BigDecimal.ZERO, over90 = BigDecimal.ZERO;
        long over90Count = 0;
        for (InvoiceFigures f : in.allIssued()) {
            Balance b = in.balances().get(f.id());
            if (f.type() != DocumentType.INVOICE || b == null || b.due().signum() <= 0 || f.issuedAt() == null) continue;
            LocalDate from = f.dueDate() != null ? f.dueDate() : f.issuedAt().atZone(InvoiceApiApplication.KSA).toLocalDate();
            long age = ChronoUnit.DAYS.between(from, in.today());
            int k = age <= 0 ? 0 : age <= 30 ? 1 : age <= 60 ? 2 : age <= 90 ? 3 : age <= 180 ? 4 : 5;
            amounts[k] = amounts[k].add(b.due());
            counts[k]++;
            totalDue = totalDue.add(b.due());
            if (age > 90) {
                over90 = over90.add(b.due());
                over90Count++;
            }
            String name = customerName(in, f.customerId());
            Debtor d = debtors.get(name);
            debtors.put(name, d == null ? new Debtor(name, b.due(), 1, Math.max(age, 0))
                    : new Debtor(name, d.due().add(b.due()), d.invoices() + 1, Math.max(d.oldestDays(), age)));
        }
        List<Bucket> aging = new ArrayList<>();
        for (int i = 0; i < names.length; i++) aging.add(new Bucket(names[i][0], names[i][1], amounts[i], counts[i]));
        List<Debtor> top = debtors.values().stream().sorted(Comparator.comparing(Debtor::due).reversed()).limit(10).toList();

        String topName = null;
        BigDecimal topPct = BigDecimal.ZERO;
        var biggest = byCustomer.entrySet().stream().max(Map.Entry.comparingByValue());
        if (biggest.isPresent()) {
            topName = customerName(in, biggest.get().getKey());
            topPct = pct(biggest.get().getValue(), sales);
        }
        BigDecimal collectionPct = pct(collected, sales);

        if (over90.signum() > 0)
            out.add(finding("OVERDUE_90", over90.compareTo(sales.max(BigDecimal.ONE).multiply(new BigDecimal("0.2"))) > 0 ? Severity.HIGH : Severity.MEDIUM,
                    "Receivables",
                    "SAR {amount} has been owed for more than 90 days ({n} invoices).",
                    "مبلغ {amount} ريال مستحق منذ أكثر من 90 يوماً ({n} فاتورة).",
                    "Follow up with these customers and assess an expected credit loss allowance for them (IFRS 9).",
                    "تابع هؤلاء العملاء وقدّر مخصص الخسائر الائتمانية المتوقعة لهم (المعيار الدولي 9).",
                    Map.of("amount", money(over90), "n", String.valueOf(over90Count))));
        if (sales.signum() > 0 && collectionPct.compareTo(new BigDecimal("60")) < 0)
            out.add(finding("LOW_COLLECTION", Severity.MEDIUM, "Receivables",
                    "Only {pct}% of this period's sales has been collected.",
                    "تم تحصيل {pct}% فقط من مبيعات هذه الفترة.",
                    "Agree due dates with customers and send payment reminders from Customer-wise due.",
                    "اتفق مع العملاء على تواريخ الاستحقاق وأرسل تذكيرات السداد من صفحة المستحقات حسب العميل.",
                    Map.of("pct", collectionPct.toPlainString())));
        if (topName != null && byCustomer.size() > 1 && topPct.compareTo(new BigDecimal("40")) > 0)
            out.add(finding("CONCENTRATION", Severity.LOW, "Receivables",
                    "One customer ({name}) is {pct}% of the period's sales.",
                    "عميل واحد ({name}) يمثل {pct}% من مبيعات الفترة.",
                    "Depending on one customer is a business risk; watch their payments closely.",
                    "الاعتماد على عميل واحد مخاطرة تجارية؛ راقب مدفوعاته عن كثب.",
                    Map.of("name", topName, "pct", topPct.toPlainString())));
        return new Receivables(sales, collected, collectionPct, totalDue, aging, top, topName, topPct);
    }

    // --- cash book --------------------------------------------------------------------------------

    static CashSummary cash(Cash c, List<Finding> out) {
        BigDecimal net = nz(c.received()).subtract(nz(c.expenses()));
        BigDecimal retained = net.subtract(nz(c.drawings()));
        if (retained.signum() < 0)
            out.add(finding("NEGATIVE_CASH", Severity.HIGH, "Cash",
                    "Expenses and partner drawings are SAR {amount} more than the money received in the period.",
                    "المصروفات ومسحوبات الشركاء تزيد بمبلغ {amount} ريال عن المبالغ المستلمة خلال الفترة.",
                    "Check that all income is entered in Accounts, and agree a limit for partner drawings.",
                    "تأكد من تسجيل كل الإيرادات في الحسابات، واتفق على حد لمسحوبات الشركاء.",
                    Map.of("amount", money(retained.negate()))));
        else if (net.signum() > 0 && nz(c.drawings()).compareTo(net) > 0)
            out.add(finding("DRAWINGS_OVER_PROFIT", Severity.MEDIUM, "Cash",
                    "Partner drawings (SAR {drawings}) are more than the period's net cash profit (SAR {net}).",
                    "مسحوبات الشركاء ({drawings} ريال) أكبر من صافي الربح النقدي للفترة ({net} ريال).",
                    "Approve drawings against the profit available, as the partnership agreement sets.",
                    "اعتمد المسحوبات في حدود الربح المتاح وفق عقد الشراكة.",
                    Map.of("drawings", money(c.drawings()), "net", money(net))));
        return new CashSummary(nz(c.received()), nz(c.expenses()), net, nz(c.drawings()), retained, c.categories());
    }

    // --- documents and workforce ------------------------------------------------------------------

    /** The company documents a Saudi business needs on file; CR and VAT certificate are required. */
    private static final List<Object[]> REQUIRED = List.of(
            new Object[]{CompanyDocument.Type.CR, "Commercial registration (CR)", "السجل التجاري", true},
            new Object[]{CompanyDocument.Type.VAT_CERTIFICATE, "VAT registration certificate", "شهادة التسجيل في ضريبة القيمة المضافة", true},
            new Object[]{CompanyDocument.Type.MUNICIPAL_LICENSE, "Municipal (Balady) licence", "رخصة البلدية (بلدي)", false},
            new Object[]{CompanyDocument.Type.CHAMBER, "Chamber of Commerce membership", "عضوية الغرفة التجارية", false},
            new Object[]{CompanyDocument.Type.INSURANCE, "Insurance", "التأمين", false});

    static List<DocStatus> documents(Inputs in, List<Finding> out) {
        List<DocStatus> rows = new ArrayList<>();
        for (Object[] r : REQUIRED) {
            CompanyDocument.Type type = (CompanyDocument.Type) r[0];
            String label = (String) r[1], labelAr = (String) r[2];
            boolean required = (Boolean) r[3];
            FileOnRecord latest = in.files().stream().filter(f -> f.workerId() == null && f.type() == type)
                    .max(Comparator.comparing(f -> f.expiry() == null ? LocalDate.MAX : f.expiry())).orElse(null);
            if (latest == null) {
                rows.add(new DocStatus(type.name(), label, labelAr, null, null, "MISSING", null));
                if (required)
                    out.add(finding("DOC_MISSING", Severity.MEDIUM, "Documents",
                            "No {doc} is on file.", "لا توجد {docAr} محفوظة في النظام.",
                            "Upload it under Documents with its expiry date, so renewal reminders go out in time.",
                            "ارفعها في صفحة المستندات مع تاريخ الانتهاء ليتم إرسال تذكيرات التجديد في وقتها.",
                            Map.of("doc", label, "docAr", labelAr)));
                continue;
            }
            String status = status(latest.expiry(), in.today());
            Long left = latest.expiry() == null ? null : ExpiryRule.daysLeft(latest.expiry(), in.today());
            rows.add(new DocStatus(type.name(), label, labelAr, latest.number(), latest.expiry(), status, left));
            if ("EXPIRED".equals(status))
                out.add(finding("DOC_EXPIRED", Severity.HIGH, "Documents",
                        "The {doc} expired on {date}.", "انتهت {docAr} في {date}.",
                        "Renew it now; trading with an expired registration or licence carries government fines.",
                        "جدّدها فوراً؛ فمزاولة النشاط بسجل أو رخصة منتهية تعرّض المنشأة لغرامات حكومية.",
                        Map.of("doc", label, "docAr", labelAr, "date", dmy(latest.expiry()))));
            else if ("EXPIRING".equals(status))
                out.add(finding("DOC_EXPIRING", Severity.LOW, "Documents",
                        "The {doc} expires on {date}, in {days} days.", "تنتهي {docAr} في {date}، بعد {days} يوماً.",
                        "Start the renewal now.", "ابدأ إجراءات التجديد الآن.",
                        Map.of("doc", label, "docAr", labelAr, "date", dmy(latest.expiry()), "days", String.valueOf(left))));
        }
        return rows;
    }

    static Workforce workforce(Inputs in, List<Finding> out) {
        Map<Long, LocalDate> iqama = new HashMap<>();
        for (FileOnRecord f : in.files())
            if (f.workerId() != null && f.type() == CompanyDocument.Type.IQAMA && f.expiry() != null)
                iqama.merge(f.workerId(), f.expiry(), (a, b) -> a.isAfter(b) ? a : b);
        long active = 0, valid = 0, expiring = 0, expired = 0, missing = 0;
        for (Staff w : in.workers()) {
            if (!w.active()) continue;
            active++;
            LocalDate exp = iqama.get(w.id());
            if (exp == null) missing++;
            else switch (status(exp, in.today())) {
                case "EXPIRED" -> expired++;
                case "EXPIRING" -> expiring++;
                default -> valid++;
            }
        }
        if (expired > 0)
            out.add(finding("IQAMA_EXPIRED", Severity.HIGH, "Workforce",
                    "{n} active workers have an expired Iqama.", "{n} عامل على رأس العمل إقامته منتهية.",
                    "Renew them through Muqeem or Absher; an expired Iqama carries fines for the employer.",
                    "جدّدها عبر مقيم أو أبشر؛ فالإقامة المنتهية تعرّض صاحب العمل لغرامات.",
                    Map.of("n", String.valueOf(expired))));
        if (expiring > 0)
            out.add(finding("IQAMA_EXPIRING", Severity.LOW, "Workforce",
                    "{n} worker Iqamas expire within 60 days.", "{n} إقامة عمال تنتهي خلال 60 يوماً.",
                    "Plan the renewal fees and start renewals early.", "خطط لرسوم التجديد وابدأ التجديد مبكراً.",
                    Map.of("n", String.valueOf(expiring))));
        if (missing > 0)
            out.add(finding("IQAMA_MISSING", Severity.LOW, "Workforce",
                    "{n} active workers have no Iqama with an expiry date on file.", "{n} عامل ليس لديه إقامة بتاريخ انتهاء محفوظة.",
                    "Upload each worker's Iqama under Documents.", "ارفع إقامة كل عامل في صفحة المستندات.",
                    Map.of("n", String.valueOf(missing))));
        return new Workforce(active, valid, expiring, expired, missing);
    }

    // --- internal control -------------------------------------------------------------------------

    /** Deleting these takes money records out of the books. */
    private static final Set<String> DELETIONS = Set.of("PAYMENT_DELETED", "ACCOUNT_INCOME_DELETED", "ACCOUNT_EXPENSE_DELETED",
            "PARTNER_WITHDRAWAL_DELETED", "INVOICE_DRAFT_DELETED");

    static Controls controls(Activity a, List<Finding> out) {
        long events = a.actions().values().stream().mapToLong(Long::longValue).sum();
        long deletions = DELETIONS.stream().mapToLong(k -> a.actions().getOrDefault(k, 0L)).sum();
        long edits = a.actions().getOrDefault("PAYMENT_UPDATED", 0L);
        long failed = a.actions().getOrDefault("LOGIN_FAILED", 0L);
        long moneyDeletions = deletions - a.actions().getOrDefault("INVOICE_DRAFT_DELETED", 0L);
        if (moneyDeletions > 0)
            out.add(finding("DELETIONS", Severity.MEDIUM, "Internal control",
                    "{n} money records (payments, income, expenses or partner withdrawals) were deleted in the period.",
                    "تم حذف {n} من السجلات المالية (مدفوعات أو إيرادات أو مصروفات أو مسحوبات شركاء) خلال الفترة.",
                    "Review each deletion in the Audit log; correct entries with a new entry and have deletions approved.",
                    "راجع كل حذف في سجل التدقيق؛ صحّح القيود بقيد جديد واجعل الحذف بموافقة مسبقة.",
                    Map.of("n", String.valueOf(moneyDeletions))));
        if (failed >= 10)
            out.add(finding("FAILED_LOGINS", Severity.LOW, "Internal control",
                    "{n} failed sign-in attempts were recorded.", "تم تسجيل {n} محاولة دخول فاشلة.",
                    "Make sure every user has their own strong password and remove accounts no longer used.",
                    "تأكد أن لكل مستخدم كلمة مرور قوية خاصة به واحذف الحسابات غير المستخدمة.",
                    Map.of("n", String.valueOf(failed))));
        return new Controls(events, a.users(), deletions, edits, failed);
    }

    // --- score and calendar -----------------------------------------------------------------------

    static Score score(List<Finding> findings) {
        int s = 100;
        for (Finding f : findings) s -= PENALTY.get(f.severity());
        s = Math.max(s, 0);
        long high = findings.stream().filter(f -> f.severity() == Severity.HIGH).count();
        long medium = findings.stream().filter(f -> f.severity() == Severity.MEDIUM).count();
        long low = findings.stream().filter(f -> f.severity() == Severity.LOW).count();
        String[] rating = s >= 85 && high == 0 ? new String[]{"Good", "جيد"}
                : s >= 60 ? new String[]{"Needs attention", "يحتاج إلى متابعة"} : new String[]{"Weak", "ضعيف"};
        return new Score(s, rating[0], rating[1], high, medium, low);
    }

    /** The Saudi deadlines that follow from this period, plus document renewals in the next 90 days. */
    static List<Deadline> calendar(Inputs in, List<DocStatus> docs) {
        List<Deadline> out = new ArrayList<>();
        out.add(new Deadline("VAT return and payment for this period (ZATCA)", "الإقرار الضريبي وسداد ضريبة القيمة المضافة لهذه الفترة",
                YearMonth.from(in.to()).plusMonths(1).atEndOfMonth()));
        YearMonth next = YearMonth.from(in.today()).plusMonths(1);
        out.add(new Deadline("GOSI contributions for " + YearMonth.from(in.today()), "اشتراكات التأمينات الاجتماعية لشهر " + YearMonth.from(in.today()),
                next.atDay(15)));
        // Zakat: within 120 days after the financial year end, taken as 31 December.
        out.add(new Deadline("Zakat return and audited financial statements for " + in.to().getYear() + " (if the year ends 31 December)",
                "إقرار الزكاة والقوائم المالية المدققة لعام " + in.to().getYear() + " (إذا كانت السنة المالية تنتهي في 31 ديسمبر)",
                LocalDate.of(in.to().getYear(), 12, 31).plusDays(120)));
        for (DocStatus d : docs)
            if (d.expiry() != null && d.daysLeft() != null && d.daysLeft() <= 90)
                out.add(new Deadline("Renew " + d.label(), "تجديد " + d.labelAr(), d.expiry()));
        out.sort(Comparator.comparing(Deadline::date));
        return out;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static List<InvoiceAuditRow> issuedInPeriod(Inputs in) {
        return in.docs().stream().filter(d -> d.issuedAt() != null && d.status().billable() && inPeriod(in, d.issuedAt())).toList();
    }

    private static boolean inPeriod(Inputs in, Instant at) {
        if (at == null) return false;
        LocalDate day = at.atZone(InvoiceApiApplication.KSA).toLocalDate();
        return !day.isBefore(in.from()) && !day.isAfter(in.to());
    }

    private static String status(LocalDate expiry, LocalDate today) {
        if (expiry == null) return "VALID";
        return switch (ExpiryRule.stage(expiry, today)) {
            case EXPIRED -> "EXPIRED";
            case NOT_YET -> "VALID";
            default -> "EXPIRING";
        };
    }

    private static String customerName(Inputs in, Long id) {
        if (id == null) return "Walk-in customers";
        Party p = in.customers().get(id);
        return p == null ? "#" + id : p.name();
    }

    private static Finding finding(String code, Severity s, String area, String text, String textAr, String action, String actionAr,
                                   Map<String, String> vars) {
        return new Finding(code, s, area, text, textAr, action, actionAr, vars);
    }

    /** {@code part} as a percentage of {@code whole}, one decimal; zero when there is no whole. */
    static BigDecimal pct(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO;
        return nz(part).multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }

    /** A finding's text with its {placeholders} filled in. */
    public static String fill(String template, Map<String, String> vars) {
        String s = template;
        for (var e : vars.entrySet()) s = s.replace("{" + e.getKey() + "}", e.getValue());
        return s;
    }

    private static String dmy(LocalDate d) {
        return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(d);
    }

    static String money(BigDecimal v) {
        return String.format(java.util.Locale.US, "%,.2f", nz(v));
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static BigDecimal sum(BigDecimal[] values) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal v : values) s = s.add(v);
        return s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
