package com.fatoora.api.common;

import com.fatoora.api.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Type-ahead suggestions: values already entered anywhere in the company's records (names, addresses, cities),
 * matching what the user is typing, so the same name or address is spelled the same way every time.
 */
@RestController
@RequestMapping("/api/v1/suggest")
public class SuggestController {

    /** Each field and the table columns its suggestions come from. All tables carry organization_id. */
    static final Map<String, List<String[]>> SOURCES = Map.of(
            "party", List.of(
                    new String[]{"customer", "name_en"}, new String[]{"customer", "name_ar"},
                    new String[]{"supplier", "name"}, new String[]{"supplier", "name_ar"},
                    new String[]{"quotation", "client_name"}, new String[]{"sales_order", "client_name"},
                    new String[]{"delivery_note", "client_name"}, new String[]{"agreement", "client_name"},
                    new String[]{"account_income", "customer_name"}),
            "person", List.of(
                    new String[]{"employee", "name"}, new String[]{"worker", "name"},
                    new String[]{"supplier", "contact_person"}, new String[]{"delivery_note", "driver_name"},
                    new String[]{"delivery_note", "received_by"}, new String[]{"account_income", "received_by"}),
            "address", List.of(
                    new String[]{"customer", "address"}, new String[]{"supplier", "address"},
                    new String[]{"delivery_note", "delivery_address"}),
            "street", List.<String[]>of(new String[]{"customer", "street"}),
            "district", List.<String[]>of(new String[]{"customer", "district"}),
            "city", List.of(new String[]{"customer", "city"}, new String[]{"supplier", "city"}),
            "nationality", List.<String[]>of(new String[]{"worker", "nationality"}));

    private final JdbcTemplate jdbc;

    public SuggestController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<String> suggest(@RequestParam String field, @RequestParam(defaultValue = "") String q) {
        List<String[]> sources = SOURCES.get(field);
        String typed = q.strip();
        if (sources == null || typed.isEmpty() || typed.length() > 100) return List.of();
        StringBuilder sql = new StringBuilder("SELECT v FROM (");
        List<Object> args = new ArrayList<>();
        String like = "%" + typed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        for (int i = 0; i < sources.size(); i++) {
            String[] s = sources.get(i);
            if (i > 0) sql.append(" UNION ");
            // Table and column names come from the fixed map above, never from the request.
            sql.append("SELECT trim(").append(s[1]).append(") AS v FROM ").append(s[0])
                    .append(" WHERE organization_id = ? AND ").append(s[1]).append(" ILIKE ?");
            args.add(CurrentUser.orgId());
            args.add(like);
        }
        // Values starting with what was typed first, then the shortest.
        sql.append(") s WHERE v <> '' ORDER BY (v ILIKE ?) DESC, length(v), v LIMIT 10");
        args.add(typed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        return jdbc.queryForList(sql.toString(), String.class, args.toArray());
    }
}
