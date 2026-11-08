package com.fatoora.api.user;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * User management end to end through the real HTTP API against a local PostgreSQL: the admin adds a branch
 * login, sees everything it records and its history, and disabling it locks it out at once.
 * Run with: ./gradlew :invoice-api:test --tests '*UserManagementIT' -Ddb.it=true
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "db.it", matches = "true")
class UserManagementIT {

    @TestConfiguration
    static class CleanDatabase {
        @Bean
        FlywayMigrationStrategy cleanMigrate() {
            return (Flyway flyway) -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }

    @Autowired
    Environment env;

    private final JsonMapper json = JsonMapper.builder().build();
    private String base;

    /** A signed-in browser: its own cookie jar and CSRF token. */
    private final class Session {
        final CookieManager jar = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(jar).build();

        Session login(String email, String password) throws Exception {
            http.send(HttpRequest.newBuilder(URI.create(base + "/auth/csrf")).build(), HttpResponse.BodyHandlers.ofString());
            var res = call("POST", "/auth/login", Map.of("email", email, "password", password));
            assertEquals(200, res.statusCode(), res.body());
            return this;
        }

        HttpResponse<String> call(String method, String path, Object body) throws Exception {
            String token = jar.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("XSRF-TOKEN"))
                    .map(HttpCookie::getValue).findFirst().orElse("");
            var req = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
                    .header("X-XSRF-TOKEN", token)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        }

        JsonNode get(String path) throws Exception {
            var res = call("GET", path, null);
            assertEquals(200, res.statusCode(), path + ": " + res.body());
            return json.readTree(res.body());
        }
    }

    @BeforeAll
    void setUp() {
        base = "http://localhost:" + env.getProperty("local.server.port") + "/api/v1";
    }

    @Test
    void adminManagesUsersAndSeesEverything() throws Exception {
        Session admin = new Session().login("admin@test.local", "correct-horse-battery");

        // Add a branch login.
        var created = admin.call("POST", "/users", Map.of("email", "Laser@Test.local", "fullName", "Laser branch",
                "role", "BRANCH", "password", "laser-branch-pass"));
        assertEquals(200, created.statusCode(), created.body());
        long branchId = json.readTree(created.body()).get("id").asLong();
        assertEquals("laser@test.local", json.readTree(created.body()).get("email").asString());
        assertEquals(400, admin.call("POST", "/users", Map.of("email", "laser@test.local", "fullName", "Again",
                "role", "VIEWER", "password", "another-password")).statusCode(), "duplicate email");
        assertEquals(403, admin.call("POST", "/users", Map.of("email", "p2@test.local", "fullName", "P",
                "role", "OPERATOR", "password", "provider-pass-1")).statusCode(), "provider role is provider-only");

        // The branch records cash-book income.
        Session branch = new Session().login("laser@test.local", "laser-branch-pass");
        var income = branch.call("POST", "/accounts/income", Map.of("date", LocalDate.now().toString(),
                "customerName", "Walk-in", "details", "Laser cutting", "amount", 500, "paid", 500));
        assertEquals(201, income.statusCode(), income.body());
        assertEquals(403, branch.call("GET", "/users", null).statusCode(), "branch cannot manage users");

        // The admin sees it: in the cash book, in the user's counts, and in the history filtered to that user.
        var month = admin.get("/accounts/month?year=" + LocalDate.now().getYear() + "&month=" + LocalDate.now().getMonthValue());
        assertTrue(month.toString().contains("Laser cutting"), "admin sees the branch's income");
        var activity = admin.get("/users/" + branchId + "/activity");
        assertEquals(1, activity.get("created").get("income").asLong());
        var history = admin.get("/audit?actor=laser@test.local");
        assertTrue(history.toString().contains("ACCOUNT_INCOME_ADDED"), history.toString());
        assertTrue(history.toString().contains("\"LOGIN\""), "sign-in is in the user's history");
        assertFalse(history.toString().contains("CurrentUser["), "actor is the plain email");
        assertTrue(admin.get("/audit?q=income&from=" + LocalDate.now() + "&to=" + LocalDate.now()).toString()
                .contains("ACCOUNT_INCOME_ADDED"));
        assertEquals(0, admin.get("/audit?actor=nobody@test.local").get("totalElements").asLong());

        var users = admin.get("/users");
        assertTrue(users.toString().contains("Laser branch"));
        var dash = admin.get("/users/this-month");
        assertTrue(dash.toString().contains("Laser branch"));

        // The admin cannot lock themself out or remove the last admin.
        long adminId = admin.get("/auth/me").get("id").asLong();
        assertEquals(400, admin.call("PUT", "/users/" + adminId,
                Map.of("fullName", "Test Admin", "role", "ADMIN", "enabled", false)).statusCode());

        // Disabling the branch signs it out at once; re-enabling and a new password let it back in.
        var disabled = admin.call("PUT", "/users/" + branchId, Map.of("fullName", "Laser branch", "role", "BRANCH", "enabled", false));
        assertEquals(200, disabled.statusCode(), disabled.body());
        assertEquals(401, branch.call("GET", "/auth/me", null).statusCode(), "disabled user's session stops working");
        assertEquals(200, admin.call("PUT", "/users/" + branchId,
                Map.of("fullName", "Laser branch", "role", "BRANCH", "enabled", true)).statusCode());
        var reset = admin.call("POST", "/users/" + branchId + "/password", Map.of("password", "new-laser-password"));
        assertEquals(200, reset.statusCode(), reset.body());
        new Session().login("laser@test.local", "new-laser-password");
        assertTrue(admin.get("/audit?q=USER_").toString().contains("USER_PASSWORD_RESET"));
    }
}
