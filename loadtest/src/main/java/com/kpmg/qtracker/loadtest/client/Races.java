package com.kpmg.qtracker.loadtest.client;

import com.kpmg.qtracker.loadtest.client.Data.Control;
import com.kpmg.qtracker.loadtest.client.Data.Person;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * The same thing at the same moment from two sessions, each case several times: what each side gets back
 * (a clear refusal is wanted, never a duplicate or a 500). Prints one line per case with the statuses seen.
 */
final class Races {

    private final Data data;
    private final ExecutorService executor;
    private final List<String> report = new ArrayList<>();

    Races(Data data, ExecutorService executor) {
        this.data = data;
        this.executor = executor;
    }

    List<String> run(Function<Person, Session> signIn, int rounds) throws Exception {
        Random random = new Random(7);
        List<Person> soqm = data.of("soqm");
        Session adminA = signIn.apply(soqm.get(0));
        Session adminB = signIn.apply(soqm.get(1));

        // 1. Create Control of the same control from two sessions
        for (int n = 0; n < rounds; n++) {
            Control control = Data.any(data.controls, c -> !c.nextCycleMade && !c.draft() && !c.kdn && c.active()
                    && ("Monthly".equals(c.frequency) || "Quarterly".equals(c.frequency)), random);
            control.nextCycleMade = true;
            both("create-control same source " + control.controlId, adminA, adminB,
                    s -> s.postJson("race.create.same", "/api/controls/" + control.id + "/next-cycle", "{}",
                            Session.okOrAllowed(409)).status());
        }

        // 2. New control with the same Control ID from two sessions (POST /api/controls)
        for (int n = 0; n < rounds; n++) {
            String id = "RACE-LT-" + System.nanoTime() + "/FY27/Central";
            String body = "{\"controlId\":\"" + id + "\",\"controlFrequency\":\"Annual\",\"controlType\":\"Manual\","
                    + "\"component\":\"HR\",\"operatedBy\":\"Central\",\"priority\":\"High\","
                    + "\"nonAuditServicesApplicability\":\"No\",\"controlDescription\":\"Race\"}";
            both("new control same ID " + id, adminA, adminB,
                    s -> s.postJson("race.new.same-id", "/api/controls", body, Session.okOrAllowed(409)).status());
        }

        // 3. The same workflow move from two sessions of the Control Operator
        for (int n = 0; n < rounds; n++) {
            Control control = Data.any(data.controls, c -> "REVIEW".equals(c.status) && !c.busy.get(), random);
            if (control == null) {
                break;
            }
            control.busy.set(true);
            Person operator = data.people.stream().filter(p -> p.mail().equals(control.operators.get(0))).findFirst()
                    .orElseThrow();
            Session a = signIn.apply(operator);
            Session b = signIn.apply(operator);
            both("move same control " + control.controlId, a, b,
                    s -> s.postForm("race.move.same", "/api/workflow/move",
                            Map.of("controlId", String.valueOf(control.id), "targetStatus", "SOQM_HEAD_REVIEW"),
                            Session.okOrAllowed(409)).status());
            control.status = "SOQM_HEAD_REVIEW";
        }

        // 4. Two people save the same tab of one control at once (no version check: the last one stays)
        for (int n = 0; n < rounds; n++) {
            Control control = Data.any(data.controls, c -> "IN_PROGRESS".equals(c.status) && !c.busy.get() && !c.kdn,
                    random);
            if (control == null) {
                break;
            }
            Session.Result details = adminA.getJson("race.details.read", "/api/control-details?controlId=" + control.id);
            String a = Scenarios.replace(details.body(), "processActivities", "Version A " + n);
            String b = Scenarios.replace(details.body(), "processActivities", "Version B " + n);
            both("save same tab " + control.controlId, adminA, adminB, s -> s.postJson("race.save.same",
                    "/api/control-details", s == adminA ? a : b, Session.ok2xx()).status());
            Session.Result after = adminA.getJson("race.details.read", "/api/control-details?controlId=" + control.id);
            report.add("    stored: " + (after.body().contains("Version A " + n + "\"") ? "A"
                    : after.body().contains("Version B " + n + "\"") ? "B" : "?") + " (the other save is lost, no error)");
        }
        return report;
    }

    private void both(String name, Session a, Session b, Function<Session, Integer> call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> first = executor.submit(() -> {
            ready.countDown();
            go.await();
            return call.apply(a);
        });
        Future<Integer> second = executor.submit(() -> {
            ready.countDown();
            go.await();
            return call.apply(b);
        });
        ready.await();
        go.countDown();
        report.add(String.format("%-60s %s / %s", name, first.get(), second.get()));
    }
}
