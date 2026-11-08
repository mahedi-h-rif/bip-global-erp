package com.fatoora.api.fleet;

import com.fatoora.api.InvoiceApiApplication;
import com.fatoora.api.audit.AuditService;
import com.fatoora.api.auth.CurrentUser;
import com.fatoora.api.common.ApiException;
import com.fatoora.api.inventory.StockService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Fleet (Odoo Fleet / Oracle fleet maintenance) and trips (transportation management): vehicles with their registration,
 * insurance and inspection expiry, fuel and maintenance logs, and trips that carry delivery notes, with their costs and
 * the profit each trip made.
 */
@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {

    private static final String WRITE = "hasAnyRole('ADMIN','ACCOUNTANT')";

    public record Vehicle(Long id, @NotBlank @Size(max = 30) String plate, @NotBlank @Size(max = 150) String name,
                          @Pattern(regexp = "TRUCK|VAN|PICKUP|CAR|TRAILER|FORKLIFT") String vehicleType, @Size(max = 60) String capacity,
                          Integer modelYear, Long driverEmployeeId, String driverName, LocalDate istimaraExpiry, LocalDate insuranceExpiry,
                          LocalDate mvpiExpiry, BigDecimal odometer, Boolean active, @Size(max = 1000) String notes,
                          /** Read-only: days to the nearest document expiry; fuel + maintenance this month. */
                          Long nextExpiryDays, BigDecimal costThisMonth) {}

    public record Log(Long id, @NotNull Long vehicleId, String plate, LocalDate date, @NotNull @Pattern(regexp = "FUEL|MAINTENANCE|OTHER") String kind,
                      @NotNull @DecimalMin("0") BigDecimal amount, BigDecimal liters, BigDecimal odometer, @Size(max = 500) String description) {}

    public record Cost(Long id, @NotNull @Pattern(regexp = "FUEL|TOLL|DRIVER_ALLOWANCE|LOADING|OTHER") String kind,
                       @NotNull @DecimalMin("0.01") BigDecimal amount, @Size(max = 300) String description) {}

    public record TripRequest(@NotNull Long vehicleId, Long driverEmployeeId, LocalDate tripDate, @Size(max = 200) String origin,
                              @Size(max = 200) String destination, BigDecimal startOdometer, @Size(max = 1000) String notes,
                              List<Long> deliveryNoteIds) {}

    public record StatusRequest(@NotNull @Pattern(regexp = "IN_TRANSIT|COMPLETED|CANCELLED") String status, BigDecimal endOdometer) {}

    public record Trip(Long id, String number, Long vehicleId, String plate, Long driverEmployeeId, String driverName, LocalDate tripDate,
                       String origin, String destination, String status, BigDecimal startOdometer, BigDecimal endOdometer, String notes,
                       List<Map<String, Object>> deliveries, List<Cost> costs, BigDecimal totalCost, BigDecimal revenue, BigDecimal profit) {}

    private final JdbcTemplate jdbc;
    private final StockService numbers;
    private final AuditService audit;

    public FleetController(JdbcTemplate jdbc, StockService numbers, AuditService audit) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.audit = audit;
    }

    // --- vehicles --------------------------------------------------------------------------------

    @GetMapping("/vehicles")
    List<Vehicle> vehicles(@RequestParam(defaultValue = "false") boolean all) {
        Long orgId = CurrentUser.orgId();
        LocalDate today = LocalDate.now(InvoiceApiApplication.KSA);
        return jdbc.query("""
                SELECT v.*, e.name AS driver, COALESCE((SELECT sum(amount) FROM vehicle_log l WHERE l.vehicle_id = v.id
                  AND l.log_date >= date_trunc('month', CURRENT_DATE)), 0) AS month_cost
                FROM vehicle v LEFT JOIN employee e ON e.id = v.driver_employee_id
                WHERE v.organization_id = ? AND (? OR v.active) ORDER BY v.plate""", (rs, i) -> {
            Long next = null;
            for (String c : List.of("istimara_expiry", "insurance_expiry", "mvpi_expiry")) {
                LocalDate d = rs.getObject(c, LocalDate.class);
                if (d != null) { long days = ChronoUnit.DAYS.between(today, d); next = next == null ? days : Math.min(next, days); }
            }
            return new Vehicle(rs.getLong("id"), rs.getString("plate"), rs.getString("name"), rs.getString("vehicle_type"), rs.getString("capacity"),
                    (Integer) rs.getObject("model_year"), (Long) rs.getObject("driver_employee_id"), rs.getString("driver"),
                    rs.getObject("istimara_expiry", LocalDate.class), rs.getObject("insurance_expiry", LocalDate.class),
                    rs.getObject("mvpi_expiry", LocalDate.class), rs.getBigDecimal("odometer"), rs.getBoolean("active"), rs.getString("notes"),
                    next, rs.getBigDecimal("month_cost"));
        }, orgId, all);
    }

    @PostMapping("/vehicles")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Vehicle createVehicle(@Valid @RequestBody Vehicle v) {
        Long orgId = CurrentUser.orgId();
        requirePlate(orgId, v.plate(), null);
        Long id = jdbc.queryForObject("INSERT INTO vehicle (organization_id, plate, name) VALUES (?, ?, ?) RETURNING id", Long.class, orgId, v.plate().trim().toUpperCase(), v.name().trim());
        writeVehicle(id, v);
        audit.record(orgId, "VEHICLE_CREATED", "vehicle", id, v.plate());
        return vehicle(id);
    }

    @PutMapping("/vehicles/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    Vehicle updateVehicle(@PathVariable Long id, @Valid @RequestBody Vehicle v) {
        vehicle(id);
        requirePlate(CurrentUser.orgId(), v.plate(), id);
        writeVehicle(id, v);
        audit.record(CurrentUser.orgId(), "VEHICLE_UPDATED", "vehicle", id, v.plate());
        return vehicle(id);
    }

    private void writeVehicle(Long id, Vehicle v) {
        if (v.driverEmployeeId() != null) requireEmployee(v.driverEmployeeId());
        jdbc.update("""
                UPDATE vehicle SET plate = ?, name = ?, vehicle_type = ?, capacity = ?, model_year = ?, driver_employee_id = ?, istimara_expiry = ?,
                  insurance_expiry = ?, mvpi_expiry = ?, odometer = ?, active = ?, notes = ? WHERE id = ?""",
                v.plate().trim().toUpperCase(), v.name().trim(), v.vehicleType() == null ? "TRUCK" : v.vehicleType(), b(v.capacity()), v.modelYear(),
                v.driverEmployeeId(), v.istimaraExpiry(), v.insuranceExpiry(), v.mvpiExpiry(), v.odometer(), v.active() == null || v.active(), b(v.notes()), id);
    }

    private Vehicle vehicle(Long id) {
        return vehicles(true).stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Vehicle"));
    }

    // --- fuel / maintenance log ------------------------------------------------------------------

    @GetMapping("/logs")
    List<Log> logs(@RequestParam(required = false) Long vehicleId) {
        return jdbc.query("""
                SELECT l.*, v.plate FROM vehicle_log l JOIN vehicle v ON v.id = l.vehicle_id WHERE l.organization_id = ?
                  AND (?::bigint IS NULL OR l.vehicle_id = ?::bigint) ORDER BY l.log_date DESC, l.id DESC LIMIT 500""",
                (rs, i) -> new Log(rs.getLong("id"), rs.getLong("vehicle_id"), rs.getString("plate"), rs.getObject("log_date", LocalDate.class),
                        rs.getString("kind"), rs.getBigDecimal("amount"), rs.getBigDecimal("liters"), rs.getBigDecimal("odometer"),
                        rs.getString("description")), CurrentUser.orgId(), vehicleId, vehicleId);
    }

    /** Records fuel or maintenance and posts it as a VEHICLE expense in Accounts. */
    @PostMapping("/logs")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Log addLog(@Valid @RequestBody Log l) {
        Long orgId = CurrentUser.orgId();
        Vehicle v = vehicle(l.vehicleId());
        LocalDate date = l.date() == null ? LocalDate.now(InvoiceApiApplication.KSA) : l.date();
        Long expense = l.amount().signum() == 0 ? null : expense(orgId, date, v.plate() + " " + l.kind().toLowerCase()
                + (l.description() == null ? "" : ": " + l.description()), l.amount(), v.plate());
        Long id = jdbc.queryForObject("""
                INSERT INTO vehicle_log (organization_id, vehicle_id, log_date, kind, amount, liters, odometer, description, expense_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, l.vehicleId(), date, l.kind(), l.amount(), l.liters(),
                l.odometer(), b(l.description()), expense, CurrentUser.get().email());
        if (l.odometer() != null)
            jdbc.update("UPDATE vehicle SET odometer = GREATEST(COALESCE(odometer, 0), ?) WHERE id = ?", l.odometer(), l.vehicleId());
        return logs(l.vehicleId()).stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    // --- trips -----------------------------------------------------------------------------------

    @GetMapping("/trips")
    List<Trip> trips(@RequestParam(required = false) String status, @RequestParam(required = false) Long vehicleId) {
        Long orgId = CurrentUser.orgId();
        List<Trip> out = new ArrayList<>();
        for (Long id : jdbc.queryForList("""
                SELECT id FROM trip WHERE organization_id = ? AND (?::text IS NULL OR status = ?::text)
                  AND (?::bigint IS NULL OR vehicle_id = ?::bigint) ORDER BY trip_date DESC, id DESC LIMIT 300""",
                Long.class, orgId, status, status, vehicleId, vehicleId)) out.add(trip(orgId, id));
        return out;
    }

    @GetMapping("/trips/{id}")
    Trip getTrip(@PathVariable Long id) {
        return trip(CurrentUser.orgId(), id);
    }

    @PostMapping("/trips")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Trip createTrip(@Valid @RequestBody TripRequest r) {
        Long orgId = CurrentUser.orgId();
        Vehicle v = vehicle(r.vehicleId());
        Long driver = r.driverEmployeeId() != null ? r.driverEmployeeId() : v.driverEmployeeId();
        if (driver != null) requireEmployee(driver);
        String number = numbers.nextNumber(orgId, "TRIP", "TRP");
        Long id = jdbc.queryForObject("""
                INSERT INTO trip (organization_id, number, vehicle_id, driver_employee_id, trip_date, origin, destination, start_odometer, notes, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, orgId, number, r.vehicleId(), driver,
                r.tripDate() == null ? LocalDate.now(InvoiceApiApplication.KSA) : r.tripDate(), b(r.origin()), b(r.destination()),
                r.startOdometer() != null ? r.startOdometer() : v.odometer(), b(r.notes()), CurrentUser.get().email());
        if (r.deliveryNoteIds() != null) for (Long dn : r.deliveryNoteIds()) attach(orgId, id, dn);
        audit.record(orgId, "TRIP_CREATED", "trip", id, number + " " + v.plate());
        return trip(orgId, id);
    }

    /** Puts a delivery note on the trip; the note gets the trip's vehicle and driver. */
    @PostMapping("/trips/{id}/deliveries/{deliveryNoteId}")
    @PreAuthorize(WRITE)
    @Transactional
    Trip addDelivery(@PathVariable Long id, @PathVariable Long deliveryNoteId) {
        Long orgId = CurrentUser.orgId();
        requireOpen(trip(orgId, id));
        attach(orgId, id, deliveryNoteId);
        return trip(orgId, id);
    }

    @DeleteMapping("/trips/{id}/deliveries/{deliveryNoteId}")
    @PreAuthorize(WRITE)
    @Transactional
    Trip removeDelivery(@PathVariable Long id, @PathVariable Long deliveryNoteId) {
        Long orgId = CurrentUser.orgId();
        requireOpen(trip(orgId, id));
        jdbc.update("DELETE FROM trip_delivery WHERE trip_id = ? AND delivery_note_id = ?", id, deliveryNoteId);
        return trip(orgId, id);
    }

    /** A trip cost (fuel, toll, driver allowance…), posted as a VEHICLE expense. */
    @PostMapping("/trips/{id}/costs")
    @PreAuthorize(WRITE)
    @Transactional
    Trip addCost(@PathVariable Long id, @Valid @RequestBody Cost c) {
        Long orgId = CurrentUser.orgId();
        Trip t = trip(orgId, id);
        if ("CANCELLED".equals(t.status())) throw ApiException.conflict("trip_cancelled", "The trip is cancelled");
        Long expense = expense(orgId, t.tripDate(), t.number() + " " + c.kind().toLowerCase().replace('_', ' ')
                + (c.description() == null ? "" : ": " + c.description()), c.amount(), t.number());
        jdbc.update("INSERT INTO trip_cost (trip_id, kind, amount, description, expense_id) VALUES (?, ?, ?, ?, ?)", id, c.kind(), c.amount(), b(c.description()), expense);
        return trip(orgId, id);
    }

    @PostMapping("/trips/{id}/status")
    @PreAuthorize(WRITE)
    @Transactional
    Trip setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest r) {
        Long orgId = CurrentUser.orgId();
        Trip t = trip(orgId, id);
        if ("COMPLETED".equals(t.status()) || "CANCELLED".equals(t.status()))
            throw ApiException.conflict("trip_closed", "The trip is already " + t.status().toLowerCase());
        if (r.endOdometer() != null && t.startOdometer() != null && r.endOdometer().compareTo(t.startOdometer()) < 0)
            throw new ApiException.Validation(Map.of("endOdometer", "Lower than the start reading"));
        jdbc.update("UPDATE trip SET status = ?, end_odometer = COALESCE(?, end_odometer) WHERE id = ?", r.status(), r.endOdometer(), id);
        if (r.endOdometer() != null)
            jdbc.update("UPDATE vehicle SET odometer = GREATEST(COALESCE(odometer, 0), ?) WHERE id = ?", r.endOdometer(), t.vehicleId());
        audit.record(orgId, "TRIP_" + r.status(), "trip", id, t.number());
        return trip(orgId, id);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void attach(Long orgId, Long tripId, Long deliveryNoteId) {
        Map<String, Object> dn = jdbc.queryForList("SELECT id, status FROM delivery_note WHERE id = ? AND organization_id = ?", deliveryNoteId, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Delivery note"));
        if ("CANCELLED".equals(dn.get("status"))) throw ApiException.badRequest("delivery_cancelled", "That delivery note is cancelled");
        List<String> other = jdbc.queryForList("""
                SELECT t.number FROM trip_delivery d JOIN trip t ON t.id = d.trip_id WHERE d.delivery_note_id = ? AND t.id <> ? AND t.status <> 'CANCELLED'""",
                String.class, deliveryNoteId, tripId);
        if (!other.isEmpty()) throw ApiException.badRequest("delivery_on_trip", "That delivery note is already on trip " + other.getFirst());
        jdbc.update("INSERT INTO trip_delivery (trip_id, delivery_note_id) VALUES (?, ?) ON CONFLICT DO NOTHING", tripId, deliveryNoteId);
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT v.id AS vid, v.plate, e.name AS driver, e.phone FROM trip t JOIN vehicle v ON v.id = t.vehicle_id
                LEFT JOIN employee e ON e.id = t.driver_employee_id WHERE t.id = ?""", tripId);
        jdbc.update("""
                UPDATE delivery_note SET vehicle_id = ?, vehicle_no = ?, driver_name = COALESCE(?, driver_name), driver_phone = COALESCE(?, driver_phone)
                WHERE id = ?""", t.get("vid"), t.get("plate"), t.get("driver"), t.get("phone"), deliveryNoteId);
    }

    private Trip trip(Long orgId, Long id) {
        List<Map<String, Object>> deliveries = jdbc.queryForList("""
                SELECT n.id, n.number, n.status, COALESCE(c.name_en, c.name_ar, n.client_name) AS client,
                       COALESCE((SELECT sum(dl.quantity * COALESCE(dl.unit_price, 0)) FROM delivery_note_line dl WHERE dl.delivery_note_id = n.id), 0) AS value
                FROM trip_delivery d JOIN delivery_note n ON n.id = d.delivery_note_id LEFT JOIN customer c ON c.id = n.customer_id
                WHERE d.trip_id = ? ORDER BY n.number""", id);
        List<Cost> costs = jdbc.query("SELECT * FROM trip_cost WHERE trip_id = ? ORDER BY id",
                (rs, i) -> new Cost(rs.getLong("id"), rs.getString("kind"), rs.getBigDecimal("amount"), rs.getString("description")), id);
        BigDecimal cost = costs.stream().map(Cost::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Revenue: the goods value (excl. VAT) of the delivery notes carried, at their order/invoice prices.
        BigDecimal revenue = deliveries.stream().map(d -> (BigDecimal) d.get("value")).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return jdbc.query("""
                SELECT t.*, v.plate, e.name AS driver FROM trip t JOIN vehicle v ON v.id = t.vehicle_id LEFT JOIN employee e ON e.id = t.driver_employee_id
                WHERE t.id = ? AND t.organization_id = ?""",
                (rs, i) -> new Trip(rs.getLong("id"), rs.getString("number"), rs.getLong("vehicle_id"), rs.getString("plate"),
                        (Long) rs.getObject("driver_employee_id"), rs.getString("driver"), rs.getObject("trip_date", LocalDate.class),
                        rs.getString("origin"), rs.getString("destination"), rs.getString("status"), rs.getBigDecimal("start_odometer"),
                        rs.getBigDecimal("end_odometer"), rs.getString("notes"), deliveries, costs, cost, revenue, revenue.subtract(cost)), id, orgId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Trip"));
    }

    private Long expense(Long orgId, LocalDate date, String description, BigDecimal amount, String ref) {
        return jdbc.queryForObject("""
                INSERT INTO account_expense (organization_id, entry_date, description, category, amount, method, invoice_number, source, created_by)
                VALUES (?, ?, ?, 'VEHICLE', ?, 'CASH', ?, 'MANUAL', ?) RETURNING id""", Long.class, orgId, date, description, amount, ref,
                CurrentUser.get().email());
    }

    private static void requireOpen(Trip t) {
        if ("COMPLETED".equals(t.status()) || "CANCELLED".equals(t.status()))
            throw ApiException.conflict("trip_closed", "The trip is " + t.status().toLowerCase());
    }

    private void requirePlate(Long orgId, String plate, Long exceptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM vehicle WHERE organization_id = ? AND lower(plate) = lower(?) AND (?::bigint IS NULL OR id <> ?::bigint)",
                Integer.class, orgId, plate.trim(), exceptId, exceptId);
        if (n != null && n > 0) throw new ApiException.Validation(Map.of("plate", "A vehicle with this plate already exists"));
    }

    private void requireEmployee(Long id) {
        if (jdbc.queryForObject("SELECT count(*) FROM employee WHERE id = ? AND organization_id = ?", Integer.class, id, CurrentUser.orgId()) == 0)
            throw ApiException.notFound("Employee");
    }

    private static String b(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
