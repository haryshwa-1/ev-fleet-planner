package com.moveinsync.evfleet.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.evfleet.app.service.ShiftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole service over HTTP against a real PostgreSQL: login to published plan. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestDatabase.class)
class ApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    ShiftService shiftService;
    @Autowired
    com.moveinsync.evfleet.app.service.DemoSeeder seeder;

    String admin;
    String operator;
    String employee;
    String otherTenant;
    long shift;

    @BeforeEach
    void setUp() throws Exception {
        admin = login("admin@acme");
        operator = login("operator@acme");
        employee = login("employee@acme");
        otherTenant = login("admin@globex");
        shift = newPlannedShift();
    }

    private String login(String user) throws Exception {
        String body = json.writeValueAsString(Map.of("username", user, "password", "demo-password"));
        return read(mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())).get("token").asText();
    }

    private long newPlannedShift() throws Exception {
        long id = read(send("/admin/shifts", admin, Map.of("cabs", 120, "hubs", 3, "chargersPerHub", 4, "trips", 600, "seed", 3))
                .andExpect(status().isCreated())).get("shiftId").asLong();
        send("/shifts/" + id + "/plan", admin, Map.of()).andExpect(status().isOk());
        return id;
    }

    private ResultActions send(String path, String token, Map<String, ?> body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions fetch(String path, String token) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> soc(String cab, int percent, String at, long seq, String key) {
        return Map.of("cabId", cab, "socPercent", percent, "observedAt", at, "seq", seq, "idempotencyKey", key);
    }

    private ResultActions drift(Map<String, ?> event) throws Exception {
        return send("/shifts/" + shift + "/events/soc", operator, event);
    }

    // ---- planning and reads

    @Test
    void aPlanIsPublishedWithItsSummary() throws Exception {
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.totalTrips").value(600))
                .andExpect(jsonPath("$.gap").isNumber());
        fetch("/shifts/" + shift + "/plan/validate", operator).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        fetch("/shifts/" + shift + "/cabs/CAB-001/timeline", operator).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").exists());
        fetch("/shifts/" + shift + "/plan/unassigned", operator).andExpect(status().isOk());
    }

    @Test
    void theSeededThreeHundredCabShiftIsReady() throws Exception {
        JsonNode versions = read(fetch("/shifts/1/plan/summary", admin).andExpect(status().isOk()));
        assertThat(versions.get("totalTrips").asInt()).isEqualTo(1500);
        assertThat(versions.get("planningMillis").asLong()).isLessThan(30_000);
    }

    // ---- security

    @Test
    void noTokenMeansUnauthenticated() throws Exception {
        mvc.perform(get("/shifts/" + shift + "/plan/summary")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aWrongPasswordIsRefused() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "admin@acme", "password", "nope"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void rolesLimitWhatEachUserCanDo() throws Exception {
        send("/shifts/" + shift + "/plan", operator, Map.of()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        send("/admin/shifts", operator, Map.of()).andExpect(status().isForbidden());
        fetch("/shifts/" + shift + "/plan/summary", employee).andExpect(status().isForbidden());
        drift(soc("CAB-001", 50, "19:30", 1, "role-1")).andExpect(status().isOk());
        send("/shifts/" + shift + "/events/soc", employee, soc("CAB-001", 50, "19:31", 2, "role-2"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anEmployeeSeesOnlyTheirOwnTrip() throws Exception {
        fetch("/me/trip", employee).andExpect(status().isOk()).andExpect(jsonPath("$.tripId").value("T-0001"));
        fetch("/me/trip", operator).andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantsShiftDoesNotExist() throws Exception {
        for (String path : List.of("/plan/summary", "/plan/unassigned", "/plan/validate", "/plan/versions",
                "/cabs/CAB-001/timeline")) {
            fetch("/shifts/" + shift + path, otherTenant).andExpect(status().isNotFound());
        }
        send("/shifts/" + shift + "/plan", otherTenant, Map.of()).andExpect(status().isNotFound());
        send("/shifts/" + shift + "/events/soc", otherTenant, soc("CAB-001", 50, "19:30", 1, "x-tenant"))
                .andExpect(status().isNotFound());
        send("/shifts/" + shift + "/chargers/H1-C1/failure", otherTenant,
                Map.of("failedAt", "19:00", "idempotencyKey", "x-tenant-2")).andExpect(status().isNotFound());
    }

    // ---- live events

    @Test
    void aDriftEventPublishesAValidNewVersionWithADiff() throws Exception {
        JsonNode r = read(drift(soc("CAB-005", 25, "19:30", 1, "d-1")).andExpect(status().isOk()));
        assertThat(r.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(r.get("planVersion").asInt()).isEqualTo(2);
        assertThat(r.get("replanMillis").asLong()).isLessThan(2_000);
        fetch("/shifts/" + shift + "/plan/validate", operator).andExpect(jsonPath("$.valid").value(true));
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.asOf").value("19:30"));
    }

    @Test
    void theSameEventTwiceIsAppliedOnce() throws Exception {
        JsonNode first = read(drift(soc("CAB-005", 40, "19:30", 1, "same")).andExpect(status().isOk()));
        JsonNode again = read(drift(soc("CAB-005", 40, "19:30", 1, "same")).andExpect(status().isOk()));
        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(again.get("duplicate").asBoolean()).isTrue();
        assertThat(again.get("planVersion")).isEqualTo(first.get("planVersion"));
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void aKeyReusedForADifferentEventIsRefusedInsteadOfLosingIt() throws Exception {
        drift(soc("CAB-005", 40, "19:30", 1, "reused")).andExpect(status().isOk());
        drift(soc("CAB-009", 18, "19:30", 1, "reused")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        send("/shifts/" + shift + "/chargers/H1-C1/failure", operator,
                Map.of("failedAt", "19:30", "idempotencyKey", "reused")).andExpect(status().isConflict());
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void aTimeAfterTheShiftCannotMoveTheClockAndCanBeCorrected() throws Exception {
        drift(soc("CAB-005", 40, "23:30+1", 1, "typo")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EVENT_TIME_AFTER_SHIFT"));
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.version").value(1));
        // Nothing was recorded, so the same key and sequence work once the time is right.
        JsonNode fixed = read(drift(soc("CAB-005", 40, "19:30", 1, "typo")).andExpect(status().isOk()));
        assertThat(fixed.get("effectiveAt").asText()).isEqualTo("19:30");
    }

    @Test
    void anOlderEventFromACabIsRefused() throws Exception {
        drift(soc("CAB-005", 40, "19:30", 5, "seq-5")).andExpect(status().isOk());
        drift(soc("CAB-005", 45, "19:25", 4, "seq-4")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_OUT_OF_ORDER"));
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void aChargerFailureMovesItsSessionsAndCannotHappenTwice() throws Exception {
        String path = "/shifts/" + shift + "/chargers/H1-C1/failure";
        JsonNode r = read(send(path, operator, Map.of("failedAt", "19:00", "idempotencyKey", "f-1")).andExpect(status().isOk()));
        assertThat(r.get("outcome").asText()).isEqualTo("APPLIED");
        fetch("/shifts/" + shift + "/plan/validate", operator).andExpect(jsonPath("$.valid").value(true));
        send(path, operator, Map.of("failedAt", "19:00", "idempotencyKey", "f-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        send(path, operator, Map.of("failedAt", "19:30", "idempotencyKey", "f-2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHARGER_ALREADY_FAILED"));
    }

    @Test
    void theOvernightScheduleIsCheckedAndLeavesTheLivePlanAlone() throws Exception {
        fetch("/shifts/" + shift + "/plan/overnight", operator).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.notReady").isEmpty());
        fetch("/shifts/" + shift + "/plan/overnight", employee).andExpect(status().isForbidden());
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anUnplannedNewShiftChangesNothingForEmployeesOrARestart() throws Exception {
        send("/admin/shifts", admin, Map.of("cabs", 10, "hubs", 1, "chargersPerHub", 2, "trips", 20, "seed", 9))
                .andExpect(status().is2xxSuccessful());
        send("/admin/shifts", otherTenant, Map.of("cabs", 10, "hubs", 1, "chargersPerHub", 2, "trips", 20, "seed", 9))
                .andExpect(status().is2xxSuccessful());
        fetch("/me/trip", employee).andExpect(status().isOk()).andExpect(jsonPath("$.tripId").value("T-0001"));
        seeder.run(null); // what a restart does; must not treat the unplanned shift as a failed seed
    }

    @Test
    void historyAndBackupTripsSurviveEventsAcrossTheShift() throws Exception {
        List<String> finishedEarly = new ArrayList<>();
        for (int n = 1; n <= 20; n++) finishedEarly.addAll(tripsEndingBefore(n, 18 * 60 + 30));
        assertThat(finishedEarly).isNotEmpty();

        drift(soc("CAB-005", 60, "18:30", 1, "chain-1")).andExpect(status().isOk());
        drift(soc("CAB-009", 60, "21:00", 1, "chain-2")).andExpect(status().isOk());
        drift(soc("CAB-012", 60, "23:30", 1, "chain-3")).andExpect(status().isOk());

        JsonNode s = read(fetch("/shifts/" + shift + "/plan/summary", operator));
        assertThat(s.get("backupTrips").asLong()).isPositive();
        assertThat(s.get("electricTrips").asLong() + s.get("backupTrips").asLong()).isEqualTo(s.get("totalTrips").asLong());
        List<String> stillThere = new ArrayList<>();
        for (int n = 1; n <= 20; n++) stillThere.addAll(tripsEndingBefore(n, 18 * 60 + 30));
        assertThat(stillThere).isEqualTo(finishedEarly);
    }

    private List<String> tripsEndingBefore(int cab, int minute) throws Exception {
        List<String> trips = new ArrayList<>();
        for (JsonNode a : read(fetch("/shifts/" + shift + "/cabs/" + String.format("CAB-%03d", cab) + "/timeline", operator))) {
            if ("TRIP".equals(a.get("type").asText()) && a.get("end").asInt() < minute) trips.add(a.get("tripId").asText());
        }
        return trips;
    }

    @Test
    void aFreshPlanCannotOverwriteALiveShift() throws Exception {
        drift(soc("CAB-005", 40, "19:30", 1, "live")).andExpect(status().isOk());
        send("/shifts/" + shift + "/plan", admin, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SHIFT_IS_LIVE"));
    }

    @Test
    void aStrandedCabLeavesServiceAndDispatchIsTold() throws Exception {
        // Mid-trip at 1% the cab cannot finish what it is doing.
        JsonNode timeline = read(fetch("/shifts/" + shift + "/cabs/CAB-005/timeline", operator));
        JsonNode trip = null;
        for (JsonNode a : timeline) if ("TRIP".equals(a.get("type").asText()) && a.get("end").asInt() - a.get("start").asInt() > 5) trip = a;
        assertThat(trip).isNotNull();
        int minute = trip.get("start").asInt() + 2;
        String at = String.format("%02d:%02d%s", minute % 1440 / 60, minute % 60, minute >= 1440 ? "+1" : "");

        JsonNode r = read(drift(soc("CAB-005", 1, at, 1, "stranded")).andExpect(status().isOk()));
        assertThat(r.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(r.get("strandedCab").asText()).isEqualTo("CAB-005");
        assertThat(r.get("tripsToBackup").asLong()).isPositive();
        fetch("/shifts/" + shift + "/plan/validate", operator).andExpect(jsonPath("$.valid").value(true));
        // The answer is remembered, so a retry gets the same one.
        drift(soc("CAB-005", 1, at, 1, "stranded")).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
    }

    @Test
    void manyEventsAtOnceProduceStrictlyOrderedValidVersions() throws Exception {
        int events = 50;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<Integer>> sent = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            int n = i;
            sent.add(pool.submit(() -> drift(soc(String.format("CAB-%03d", n % 100 + 1), 40 + n % 40, "19:30", n + 1, "c-" + n))
                    .andReturn().getResponse().getStatus()));
        }
        int applied = 0;
        for (Future<Integer> f : sent) if (f.get() == 200) applied++;
        pool.shutdown();

        assertThat(applied).isGreaterThan(events / 2);
        JsonNode versions = read(fetch("/shifts/" + shift + "/plan/versions", operator));
        assertThat(versions).hasSize(applied + 1);
        for (int i = 0; i < versions.size(); i++) assertThat(versions.get(i).get("version").asInt()).isEqualTo(i + 1);
        fetch("/shifts/" + shift + "/plan/validate", operator).andExpect(jsonPath("$.valid").value(true));
    }

    // ---- failure handling and operations

    @Test
    void aPlannerTimeoutKeepsThePreviousPlan() throws Exception {
        ReflectionTestUtils.setField(shiftService, "timeoutMillis", 1L);
        try {
            send("/shifts/" + shift + "/plan", admin, Map.of()).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PLANNER_TIMEOUT"));
        } finally {
            ReflectionTestUtils.setField(shiftService, "timeoutMillis", 30_000L);
        }
        fetch("/shifts/" + shift + "/plan/summary", operator).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void errorsAreCleanJsonWithoutInternals() throws Exception {
        String body = drift(Map.of("cabId", "CAB-001", "socPercent", 500, "observedAt", "19:30", "seq", 1, "idempotencyKey", "bad"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "at com.");
        assertThat(json.readTree(body).get("code").asText()).isEqualTo("BAD_REQUEST");
        fetch("/no/such/route", operator).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        drift(soc("CAB-001", 50, "not-a-time", 1, "bad-time")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_TIME"));
        drift(soc("NO-SUCH-CAB", 50, "19:30", 1, "no-cab")).andExpect(status().isNotFound());
    }

    @Test
    void metricsAndCorrelationIdsAreExposed() throws Exception {
        drift(soc("CAB-005", 40, "19:30", 1, "m-1")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
        String prometheus = mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(prometheus).contains("replan_latency_seconds", "events_total", "unassigned_trips",
                "event_lag_minutes", "events_in_flight", "events_retries_total", "http_server_requests_seconds");
    }
}
