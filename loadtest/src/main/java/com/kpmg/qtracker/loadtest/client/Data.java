package com.kpmg.qtracker.loadtest.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * The made-up accounts and controls the generator wrote (users.csv, controls.csv), with each control's current
 * status as the client moved it. A control is claimed while one virtual user moves it, so the client does not race
 * itself in the mixed load (the races are their own scenario).
 */
final class Data {

    record Person(String mail, String kind) {
        boolean isKdn() {
            return "kdn".equals(kind);
        }

        boolean seesAll() {
            return kind.equals("soqm") || kind.equals("editall") || kind.equals("roall");
        }
    }

    static final class Control {
        final long id;
        final String controlId;
        final String frequency;
        final boolean kdn;
        final String component;
        final List<String> facilitators;
        final List<String> operators;
        final List<String> soqm;
        final List<String> owners;
        final List<String> shared;
        final List<String> files;
        volatile String status;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile boolean nextCycleMade;

        Control(String[] f) {
            id = Long.parseLong(f[0]);
            controlId = f[1];
            status = f[2];
            frequency = f[3];
            kdn = Boolean.parseBoolean(f[4]);
            component = f[5];
            facilitators = list(f[6]);
            operators = list(f[7]);
            soqm = list(f[8]);
            owners = list(f[9]);
            shared = list(f[10]);
            files = list(f.length > 11 ? f[11] : "");
        }

        boolean draft() {
            return "DRAFT".equals(status);
        }

        boolean on(String mail) {
            return facilitators.contains(mail) || operators.contains(mail) || soqm.contains(mail)
                    || owners.contains(mail) || shared.contains(mail);
        }

        private static List<String> list(String value) {
            return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split("\\|"));
        }
    }

    final List<Person> people = new ArrayList<>();
    final List<Control> controls = new ArrayList<>();
    final Map<String, List<Control>> byPerson = new HashMap<>();
    final List<Control> kdnControls = new ArrayList<>();
    final List<Control> withFiles = new ArrayList<>();

    static Data load(Path folder) throws IOException {
        Data data = new Data();
        List<String> users = Files.readAllLines(folder.resolve("users.csv"), StandardCharsets.UTF_8);
        for (String line : users.subList(1, users.size())) {
            String[] f = line.split(",", -1);
            data.people.add(new Person(f[0], f[1]));
        }
        List<String> lines = Files.readAllLines(folder.resolve("controls.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            Control control = new Control(line.split(",", -1));
            data.controls.add(control);
            for (Person person : data.people) {
                if (control.on(person.mail())) {
                    data.byPerson.computeIfAbsent(person.mail(), key -> new ArrayList<>()).add(control);
                }
            }
            if (control.kdn) {
                data.kdnControls.add(control);
            }
            if (!control.files.isEmpty()) {
                data.withFiles.add(control);
            }
        }
        return data;
    }

    List<Person> of(String kind) {
        return people.stream().filter(person -> person.kind().equals(kind)).toList();
    }

    /** A control the person may open (not a draft unless SoQM; KDN: the KDN controls), or null. */
    Control visible(Person person, Random random) {
        if (person.isKdn()) {
            return any(kdnControls, control -> !control.draft(), random);
        }
        if (person.seesAll()) {
            return any(controls, control -> person.kind().equals("soqm") || !control.draft(), random);
        }
        return any(byPerson.getOrDefault(person.mail(), List.of()), control -> !control.draft(), random);
    }

    /** A random control of the list that passes the test, or null (a few tries, then a scan from a random place). */
    static Control any(List<Control> list, Predicate<Control> test, Random random) {
        if (list.isEmpty()) {
            return null;
        }
        for (int tries = 0; tries < 20; tries++) {
            Control control = list.get(random.nextInt(list.size()));
            if (test.test(control)) {
                return control;
            }
        }
        int start = random.nextInt(list.size());
        for (int n = 0; n < list.size(); n++) {
            Control control = list.get((start + n) % list.size());
            if (test.test(control)) {
                return control;
            }
        }
        return null;
    }
}
