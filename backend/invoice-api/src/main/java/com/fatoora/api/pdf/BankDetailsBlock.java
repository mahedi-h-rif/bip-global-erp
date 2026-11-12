package com.fatoora.api.pdf;

import com.fatoora.api.organization.Organization;

import java.util.ArrayList;
import java.util.List;

/**
 * The company's bank details for documents a customer pays from (invoice, quotation, proforma, sales order,
 * agreement): a QR code holding the same details, beside a table with English labels, the value and Arabic labels.
 * Nothing is printed until an IBAN is saved under Settings → Bank details.
 */
public final class BankDetailsBlock {

    private BankDetailsBlock() {}

    private record Row(String en, String ar, String value) {}

    private static List<Row> rows(Organization org) {
        List<Row> rows = new ArrayList<>();
        add(rows, "ACCOUNT NAME", "إسم الحساب", org.getBankAccountName());
        add(rows, "ACCOUNT NUMBER", "رقم الحساب", org.getBankAccountNo());
        add(rows, "IBAN", "رقم الآيبان", groupIban(org.getBankIban()));
        add(rows, "BANK NAME", "إسم البنك", org.getBankName());
        return rows;
    }

    /** "SA5680000450608010141252" → "SA56 8000 0450 6080 1014 1252", as printed on bank letters. */
    static String groupIban(String iban) {
        if (iban == null) return null;
        String compact = iban.replaceAll("\s+", "").toUpperCase();
        return compact.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static void add(List<Row> rows, String en, String ar, String value) {
        if (value != null && !value.isBlank()) rows.add(new Row(en, ar, value.trim()));
    }

    /** What the QR code holds: one "label: value" line per detail, readable by any phone camera. */
    static String qrPayload(Organization org) {
        StringBuilder s = new StringBuilder();
        for (Row r : rows(org)) {
            if (!s.isEmpty()) s.append('\n');
            s.append(r.en().charAt(0)).append(r.en().substring(1).toLowerCase()).append(": ").append(r.value());
        }
        return s.toString();
    }

    /** The block as XHTML, or an empty string when no IBAN is saved. */
    public static String html(Organization org) {
        if (org.getBankIban() == null || org.getBankIban().isBlank()) return "";
        List<Row> rows = rows(org);
        StringBuilder t = new StringBuilder();
        String cell = "border:1px solid #1b2233; padding:4px 8px; ";
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            t.append("<tr>");
            if (i == 0)
                t.append("<td rowspan=\"").append(rows.size()).append("\" style=\"width:27mm; padding:0 4mm 0 0; vertical-align:middle\">")
                        .append("<img src=\"").append(InvoicePdfService.qrDataUri(qrPayload(org)))
                        .append("\" style=\"width:25mm; height:25mm\"/></td>");
            t.append("<td style=\"").append(cell).append("width:21%; text-align:right; font-size:8pt\">").append(r.en()).append("</td>")
                    .append("<td style=\"").append(cell).append("text-align:center; font-weight:700; font-size:9.5pt\">")
                    .append(InvoicePdfService.esc(r.value())).append("</td>")
                    .append("<td style=\"").append(cell).append("width:14%; direction:rtl; text-align:right; font-size:8.5pt\">")
                    .append(r.ar()).append("</td>")
                    .append("</tr>");
        }
        return "<table style=\"width:100%; border-collapse:collapse; margin-top:10px; page-break-inside:avoid\">" + t + "</table>";
    }
}
