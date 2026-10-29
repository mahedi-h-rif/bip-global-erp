package com.fatoora.api.document;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.config.FatooraProperties;
import com.fatoora.api.notification.EmailSender;
import com.fatoora.api.notification.SendResult;
import com.fatoora.api.ocr.DateTextParser;
import com.fatoora.api.ocr.IqamaFieldExtractor;
import com.fatoora.api.ocr.OcrInput;
import com.fatoora.api.ocr.OcrMode;
import com.fatoora.api.ocr.OcrService;
import com.fatoora.api.ocr.PassportMrzParser;
import com.fatoora.api.ocr.PassportVizParser;
import com.fatoora.api.organization.Organization;
import com.fatoora.api.organization.OrganizationRepository;
import com.fatoora.api.user.AppUser;
import com.fatoora.api.user.AppUserRepository;
import com.fatoora.api.worker.Worker;
import com.fatoora.api.worker.WorkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a scanned document into suggested fields, and sends the expiry reminders (bell + email to admins). */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** What the scan suggests; the user checks and corrects it before saving. */
    public record Extraction(boolean ocrAvailable, String number, String holderName, String nationality,
                             LocalDate issueDate, LocalDate expiryDate, LocalDate dateOfBirth, List<String> warnings,
                             String text) {}

    private static final Pattern CR = Pattern.compile("(?<!\\d)([1-7]\\d{9})(?!\\d)");
    private static final Pattern VAT = Pattern.compile("(?<!\\d)(3\\d{13}3)(?!\\d)");
    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)(\\d{6,15})(?!\\d)");

    private final OcrService ocr;
    private final IqamaFieldExtractor iqama;
    private final PassportMrzParser mrz;
    private final PassportVizParser viz;
    private final DateTextParser dates;
    private final CompanyDocumentRepository documents;
    private final DocumentAlertRepository alerts;
    private final WorkerRepository workers;
    private final OrganizationRepository orgs;
    private final AppUserRepository users;
    private final EmailSender email;
    private final String portalUrl;

    public DocumentService(OcrService ocr, IqamaFieldExtractor iqama, PassportMrzParser mrz, PassportVizParser viz,
                           DateTextParser dates, CompanyDocumentRepository documents, DocumentAlertRepository alerts,
                           WorkerRepository workers, OrganizationRepository orgs, AppUserRepository users,
                           EmailSender email, FatooraProperties props) {
        this.ocr = ocr;
        this.iqama = iqama;
        this.mrz = mrz;
        this.viz = viz;
        this.dates = dates;
        this.documents = documents;
        this.alerts = alerts;
        this.workers = workers;
        this.orgs = orgs;
        this.users = users;
        this.email = email;
        this.portalUrl = props.portalUrl();
    }

    // --- scanning ----------------------------------------------------------------------------------

    public Extraction extract(CompanyDocument.Type type, OcrInput file) {
        List<String> warnings = new ArrayList<>();
        if (type == CompanyDocument.Type.PASSPORT) {
            Optional<String> band = ocr.extractText(file, OcrMode.MRZ);
            Optional<String> page = ocr.extractText(file, OcrMode.DOCUMENT);
            if (band.isEmpty() && page.isEmpty()) return unavailable();
            Optional<PassportMrzParser.Result> m = band.flatMap(mrz::parse).or(() -> page.flatMap(mrz::parse));
            String text = page.orElse(band.orElse(""));
            if (m.isEmpty()) {
                warnings.add("The machine-readable lines at the bottom of the passport could not be read.");
                return new Extraction(true, null, null, null, null, latest(text), null, warnings, text);
            }
            PassportMrzParser.Result r = m.get();
            if (!r.unverifiedFields().isEmpty())
                warnings.add("Check these fields, their check digits did not match: " + String.join(", ", r.unverifiedFields()));
            LocalDate issued = viz.parse(text, r.dateOfBirth(), r.expiryDate()).issueDate();
            String name = String.join(" ", java.util.stream.Stream.of(r.givenNames(), r.surname())
                    .filter(s -> s != null && !s.isBlank()).toList());
            return new Extraction(true, r.passportNumber(), blank(name), r.nationality(), issued, r.expiryDate(),
                    r.dateOfBirth(), warnings, text);
        }

        Optional<String> read = ocr.extractText(file, OcrMode.DOCUMENT);
        if (read.isEmpty()) return unavailable();
        String text = read.get();
        if (type == CompanyDocument.Type.IQAMA || type == CompanyDocument.Type.WORK_PERMIT) {
            IqamaFieldExtractor.Result r = iqama.extract(text);
            if (r.expireDate() == null) warnings.add("No expiry date found; please type it in.");
            return new Extraction(true, r.iqamaNumber(), r.name(), r.nationality(), r.issueDate(),
                    r.expireDate() != null ? r.expireDate() : latest(text), r.dateOfBirth(), warnings, text);
        }

        // Licences and certificates: the registration number, and the earliest / latest dates printed on it.
        String digits = DateTextParser.normalizeDigits(text);
        String number = first(type == CompanyDocument.Type.VAT_CERTIFICATE ? VAT : CR, digits);
        if (number == null) number = DIGITS.matcher(digits).results().map(m -> m.group(1))
                .max(Comparator.comparingInt(String::length)).orElse(null);
        List<LocalDate> found = dates.findAll(text).stream().map(DateTextParser.Found::date).distinct().sorted().toList();
        LocalDate expiry = found.isEmpty() ? null : found.getLast();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        LocalDate issue = found.stream().filter(d -> !d.isAfter(today) && (expiry == null || d.isBefore(expiry))).reduce((a, b) -> b).orElse(null);
        if (expiry == null) warnings.add("No dates found; please type the expiry date in.");
        else if (expiry.isBefore(today)) warnings.add("The latest date found is in the past; check the expiry date.");
        return new Extraction(true, number, null, null, issue, expiry, null, warnings, text);
    }

    private LocalDate latest(String text) {
        return dates.findAll(text).stream().map(DateTextParser.Found::date).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Extraction unavailable() {
        return new Extraction(false, null, null, null, null, null, null,
                List.of("The document could not be read automatically. Please fill in the details by hand."), null);
    }

    private static String first(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    // --- reminders ---------------------------------------------------------------------------------

    /** Sends every expiry reminder due today. Returns how many went out. */
    @Transactional
    public int sendDueReminders() {
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        int sent = 0;
        for (CompanyDocument d : documents.findWatched()) {
            if (!ExpiryRule.due(d.getExpiryDate(), today, d.getLastReminderOn(), d.isRemindAfterExpiry())) continue;
            try {
                remind(d, today);
                sent++;
            } catch (RuntimeException e) {
                log.error("Expiry reminder for document {} failed", d.getId(), e);
            }
        }
        return sent;
    }

    /** "Iqama of Sakat Khan (no. 2412345678) expires on 12/01/2027 (14 days left)." */
    public String message(CompanyDocument d, LocalDate today) {
        if (d.getExpiryDate() == null) return describe(d) + ".";
        long left = ExpiryRule.daysLeft(d.getExpiryDate(), today);
        String when = left > 1 ? "expires on " + DATE.format(d.getExpiryDate()) + " (" + left + " days left)"
                : left == 1 ? "expires tomorrow, " + DATE.format(d.getExpiryDate())
                : left == 0 ? "expires today, " + DATE.format(d.getExpiryDate())
                : "expired on " + DATE.format(d.getExpiryDate()) + " (" + (-left) + (left == -1 ? " day" : " days") + " ago)";
        return describe(d) + " " + when + ".";
    }

    /** A wa.me link with the message ready; to the worker's number when there is one, else the user picks the chat. */
    public static String whatsapp(String phone, String text) {
        String to = com.fatoora.api.notification.PhoneNumbers.normalize(phone);
        return "https://wa.me/" + (to == null ? "" : to) + "?text="
                + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void remind(CompanyDocument d, LocalDate today) {
        long left = ExpiryRule.daysLeft(d.getExpiryDate(), today);
        String what = describe(d);
        String message = message(d, today);

        Organization org = orgs.findById(d.getOrganizationId()).orElseThrow();
        List<String> to = users.findByOrganizationIdAndRoleAndEnabledTrue(d.getOrganizationId(), AppUser.Role.ADMIN)
                .stream().map(AppUser::getEmail).filter(e -> e != null && !e.isBlank()).distinct().toList();
        String status = to.isEmpty() ? "NO_RECIPIENT" : "SENT";
        String body = message + "\n\nRenew it and upload the new copy under Documents:\n" + portalUrl + "/documents?id=" + d.getId()
                + "\n\nTo stop these reminders for this document, switch them off on the document.\n\n" + nz(org.getLegalNameEn());
        for (String addr : to) {
            SendResult r = email.send(addr, (left < 0 ? "Expired: " : "Expiring: ") + what, body);
            if (!r.delivered()) status = "FAILED";
            else if (r.status() == com.fatoora.api.notification.NotificationLog.Status.LOGGED && status.equals("SENT")) status = "LOGGED";
        }

        DocumentAlert a = new DocumentAlert();
        a.setOrganizationId(d.getOrganizationId());
        a.setDocumentId(d.getId());
        a.setDaysLeft((int) left);
        a.setExpiryDate(d.getExpiryDate());
        a.setMessage(message);
        a.setEmailStatus(status);
        a.setEmailRecipients(to.isEmpty() ? null : String.join(", ", to));
        alerts.save(a);
        d.setLastReminderOn(today);
    }

    /** "Iqama of Sakat Khan (no. 2412345678)" or "CR (no. 7054876219)". */
    public String describe(CompanyDocument d) {
        String owner = d.getWorkerId() != null ? workers.findById(d.getWorkerId()).map(Worker::getName).orElse(null) : null;
        if (owner == null) owner = d.getHolderName();
        return d.getTitle() + (owner == null || d.getTitle().contains(owner) ? "" : " of " + owner)
                + (d.getNumber() == null ? "" : " (no. " + d.getNumber() + ")");
    }

    static String typeTitle(CompanyDocument.Type t) {
        return switch (t) {
            case IQAMA -> "Iqama";
            case PASSPORT -> "Passport";
            case WORK_PERMIT -> "Work permit";
            case DRIVING_LICENSE -> "Driving licence";
            case CR -> "Commercial Registration";
            case MUNICIPAL_LICENSE -> "Municipal licence";
            case VAT_CERTIFICATE -> "VAT certificate";
            case CHAMBER -> "Chamber of Commerce";
            case INSURANCE -> "Insurance";
            case OTHER -> "Document";
        };
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
