package com.kpmg.qtracker.loadtest.client;

import com.kpmg.qtracker.loadtest.client.Data.Control;
import com.kpmg.qtracker.loadtest.client.Data.Person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What one virtual user does, by role, chosen by weight: the lists (search, status filter, sorting, pages), the
 * dashboard, a control with every tab, saving a tab, the workflow steps and returns, files, Excel and Create
 * Control. The mix per role is in {@link #WEIGHTS}.
 */
final class Scenarios {

    enum Action { DASHBOARD, LIST_ALL, LIST_COMPONENT, LIST_KDN, CARD, SAVE_DETAILS, MOVE, DOWNLOAD, DOWNLOAD_BIG, UPLOAD,
        EXPORT_LIST, EXPORT_ONE, CREATE_CONTROL }

    /** Per role: the actions and their weights (out of 100). */
    static final Map<String, Object[][]> WEIGHTS = Map.of(
            "soqm", new Object[][]{{Action.DASHBOARD, 14}, {Action.LIST_ALL, 18}, {Action.LIST_COMPONENT, 8},
                    {Action.LIST_KDN, 4}, {Action.CARD, 29}, {Action.SAVE_DETAILS, 5}, {Action.MOVE, 7},
                    {Action.DOWNLOAD, 5}, {Action.UPLOAD, 2}, {Action.EXPORT_LIST, 3}, {Action.EXPORT_ONE, 3},
                    {Action.CREATE_CONTROL, 2}},
            "edit", new Object[][]{{Action.DASHBOARD, 20}, {Action.LIST_ALL, 22}, {Action.LIST_COMPONENT, 8},
                    {Action.CARD, 30}, {Action.SAVE_DETAILS, 6}, {Action.MOVE, 9}, {Action.DOWNLOAD, 4},
                    {Action.UPLOAD, 1}},
            "ro", new Object[][]{{Action.DASHBOARD, 30}, {Action.LIST_ALL, 30}, {Action.LIST_COMPONENT, 8},
                    {Action.CARD, 28}, {Action.DOWNLOAD, 4}},
            "kdn", new Object[][]{{Action.LIST_KDN, 55}, {Action.CARD, 40}, {Action.DOWNLOAD, 5}});

    static final String[] COMPONENTS = {"HR", "EP", "A&C", "RER", "INTR", "I&C", "GOV", "TECHR", "M&R", "RAP"};
    private static final String[] SEARCHES = {"", "", "", "", "LT-0", "Central", "FY27", "review", "KDN", "OCT", "zzz"};
    private static final String[] STATUSES = {"", "", "", "IN_PROGRESS", "IN_REVIEW", "REVIEW", "SOQM_HEAD_REVIEW",
            "PROCESS_OWNER_REVIEW", "COMPLETED", "ACTIVE", "OVERDUE", "YOUR_TURN", "DRAFT"};
    private static final String[] SORTS = {"", "id", "type", "frequency", "facilitator", "operator", "owner", "soqm",
            "category", "date", "status"};
    private static final Pattern STRING_FIELD = Pattern.compile("\"(%s)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|null)");

    private final Data data;
    private final byte[] uploadContent;

    Scenarios(Data data, byte[] uploadContent) {
        this.data = data;
        this.uploadContent = uploadContent;
    }

    static String roleOf(Person person) {
        return switch (person.kind()) {
            case "soqm" -> "soqm";
            case "kdn" -> "kdn";
            case "ro", "roall" -> "ro";
            default -> "edit";
        };
    }

    Action pick(Person person, Random random) {
        Object[][] weights = WEIGHTS.get(roleOf(person));
        int roll = random.nextInt(100);
        for (Object[] weight : weights) {
            roll -= (int) weight[1];
            if (roll < 0) {
                return (Action) weight[0];
            }
        }
        return (Action) weights[0][0];
    }

    void run(Action action, Session session, Random random) {
        Person person = session.person;
        switch (action) {
            case DASHBOARD -> dashboard(session);
            case LIST_ALL -> session.page("list.all", "/component/ALL" + listQuery(random));
            case LIST_COMPONENT -> session.page("list.component",
                    "/component/" + Session.encode(COMPONENTS[random.nextInt(COMPONENTS.length)]) + listQuery(random));
            case LIST_KDN -> session.page("list.kdn", "/kdn" + listQuery(random));
            case CARD -> {
                Control control = data.visible(person, random);
                if (control != null) {
                    card(session, control, !person.kind().startsWith("ro") && !person.isKdn());
                }
            }
            case SAVE_DETAILS -> saveDetails(session, random);
            case MOVE -> move(session, random);
            case DOWNLOAD -> download(session, random);
            case DOWNLOAD_BIG -> downloadBig(session, random);
            case UPLOAD -> upload(session, random);
            case EXPORT_LIST -> session.get("export.list", "/component/ALL/export" + listQuery(random)
                    .replaceAll("[&?](page|size)=\\d+", ""), Session.ok2xx(), true);
            case EXPORT_ONE -> {
                Control control = Data.any(data.controls, c -> "COMPLETED".equals(c.status), random);
                if (control != null) {
                    session.get("export.one", "/api/controls/" + control.id + "/export/completed", Session.ok2xx(), true);
                }
            }
            case CREATE_CONTROL -> createControl(session, random);
        }
    }

    private void dashboard(Session session) {
        boolean organisation = "soqm".equals(session.person.kind());
        session.page("dash.page", "/");
        session.getJson("dash.chart.frequency", organisation ? "/api/dashboard/admin/frequency"
                : "/api/dashboard/my/frequency");
        session.getJson("dash.chart.component", organisation ? "/api/dashboard/admin/component-breakdown"
                : "/api/dashboard/my/component");
        session.getJson("dash.deadlines", "/api/dashboard/deadline-countdown?days=3&limit=10");
        LocalDate first = LocalDate.now().withDayOfMonth(1);
        session.getJson("dash.calendar", "/api/dashboard/deadline-calendar?start=" + first + "&end="
                + first.plusMonths(1).minusDays(1));
    }

    /** View Control and what its tabs load (Assignment, Details, Documentation, History, the people lists). */
    static void card(Session session, Control control, boolean editor) {
        long id = control.id;
        session.page("card.page", "/view-control/" + id);
        session.getJson("card.permissions", "/api/permissions/" + id);
        session.getJson("card.assignment", "/api/control-assignment?controlId=" + id);
        session.getJson("card.details", "/api/control-details?controlId=" + id);
        session.getJson("card.attachments", "/api/attachments/info/" + id);
        session.getJson("card.changelog", "/api/controls/" + id + "/changelog");
        if (editor) {
            session.getJson("card.users", "/api/users/all?controlId=" + id);
        }
    }

    static String listQuery(Random random) {
        StringBuilder query = new StringBuilder();
        add(query, "q", SEARCHES[random.nextInt(SEARCHES.length)]);
        add(query, "status", STATUSES[random.nextInt(STATUSES.length)]);
        String sort = SORTS[random.nextInt(SORTS.length)];
        add(query, "sort", sort);
        if (!sort.isEmpty()) {
            add(query, "dir", random.nextBoolean() ? "asc" : "desc");
        }
        int page = random.nextInt(10) < 6 ? 1 : 2 + random.nextInt(8);
        if (page > 1) {
            add(query, "page", String.valueOf(page));
        }
        if (random.nextInt(4) == 0) {
            add(query, "size", "50");
        }
        return query.toString();
    }

    private static void add(StringBuilder query, String name, String value) {
        if (value != null && !value.isEmpty()) {
            query.append(query.isEmpty() ? '?' : '&').append(name).append('=').append(Session.encode(value));
        }
    }

    /** The field the person's step writes, read with the tab and saved back with the rest unchanged. */
    private void saveDetails(Session session, Random random) {
        Person person = session.person;
        String mail = person.mail();
        Control control;
        String field;
        if ("soqm".equals(person.kind())) {
            control = Data.any(data.controls, c -> !c.draft() && !"COMPLETED".equals(c.status), random);
            field = "processActivities";
        } else {
            List<Control> own = data.byPerson.getOrDefault(mail, List.of());
            control = Data.any(own, c -> ("IN_PROGRESS".equals(c.status) && c.facilitators.contains(mail))
                    || ("PROCESS_OWNER_REVIEW".equals(c.status) && c.owners.contains(mail)), random);
            field = control == null ? null : "IN_PROGRESS".equals(control.status) ? "controlStepsPerformed"
                    : "processOwnerComments";
        }
        if (control == null || !control.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            Session.Result details = session.getJson("card.details", "/api/control-details?controlId=" + control.id);
            if (!details.ok() || details.body() == null) {
                return;
            }
            String body = replace(details.body(), field, field + " saved by load test " + System.nanoTime());
            session.postJson("save.details", "/api/control-details", body, Session.ok2xx());
        } finally {
            control.busy.set(false);
        }
    }

    static String replace(String json, String field, String value) {
        Matcher matcher = Pattern.compile(String.format(STRING_FIELD.pattern(), Pattern.quote(field))).matcher(json);
        String quoted = "\"" + field + "\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return matcher.find() ? json.substring(0, matcher.start()) + quoted + json.substring(matcher.end())
                : json.replaceFirst("\\{", "{" + quoted.replace("$", "\\$") + ",");
    }

    /** The person's own step on one of their controls: forward, or now and then a return with a comment. */
    private void move(Session session, Random random) {
        String mail = session.person.mail();
        List<Control> own = "soqm".equals(session.person.kind())
                ? data.controls : data.byPerson.getOrDefault(mail, List.of());
        Control control = Data.any(own, c -> !c.busy.get() && step(c, mail) != null, random);
        if (control == null || !control.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            String from = control.status;
            String[] step = step(control, mail);
            if (step == null) {
                return;
            }
            boolean back = step.length > 1 && random.nextInt(100) < 20;
            String target = back ? step[1] : step[0];
            Session.Result result = session.postForm(back ? "move.return" : "move.forward", "/api/workflow/move",
                    back ? Map.of("controlId", String.valueOf(control.id), "targetStatus", target,
                            "comments", "Returned by the load test")
                            : Map.of("controlId", String.valueOf(control.id), "targetStatus", target),
                    Session.okOrAllowed(409));
            if (result.ok() && from.equals(control.status)) {
                control.status = target;
            }
        } finally {
            control.busy.set(false);
        }
    }

    /** {forward, return} of the person's step on the control in its status; null when it is not their turn. */
    static String[] step(Control control, String mail) {
        return switch (control.status) {
            case "IN_PROGRESS" -> control.facilitators.contains(mail) ? new String[]{"REVIEW"} : null;
            case "REVIEW" -> control.operators.contains(mail) ? new String[]{"SOQM_HEAD_REVIEW", "IN_PROGRESS"} : null;
            case "SOQM_HEAD_REVIEW" -> control.soqm.contains(mail) ? new String[]{"PROCESS_OWNER_REVIEW", "REVIEW"} : null;
            case "PROCESS_OWNER_REVIEW" -> control.owners.contains(mail) ? new String[]{"COMPLETED", "REVIEW"} : null;
            default -> null;
        };
    }

    private void download(Session session, Random random) {
        Person person = session.person;
        Control control = Data.any(data.withFiles, c -> person.kind().equals("soqm")
                || (person.isKdn() ? c.kdn : (person.seesAll() || c.on(person.mail()))), random);
        if (control == null) {
            return;
        }
        String file = control.files.get(random.nextInt(control.files.size()));
        session.get(control.files.size() > 1 ? "file.download.big" : "file.download.small",
                "/api/attachments/download/" + Session.encode(file).replace("+", "%20") + "?controlId=" + control.id,
                Session.ok2xx(), true);
    }

    /** One of the 3-5 MB files (the heavy users of --heavy-users). */
    private void downloadBig(Session session, Random random) {
        Control control = Data.any(data.withFiles, c -> c.files.size() > 1, random);
        if (control != null) {
            String file = control.files.get(random.nextInt(control.files.size()));
            session.get("file.download.big", "/api/attachments/download/" + Session.encode(file).replace("+", "%20")
                    + "?controlId=" + control.id, Session.ok2xx(), true);
        }
    }

    private void upload(Session session, Random random) {
        Person person = session.person;
        Control control = "soqm".equals(person.kind())
                // Any control SoQM may change: a few controls would reach the limit of 50 files (a 400 by the rule)
                ? Data.any(data.controls, c -> !c.kdn && !c.draft(), random)
                : Data.any(data.byPerson.getOrDefault(person.mail(), List.of()),
                        c -> "IN_PROGRESS".equals(c.status) && c.facilitators.contains(person.mail()), random);
        if (control == null) {
            return;
        }
        String reason = "COMPLETED".equals(control.status) ? "Load test upload" : null;
        session.upload("file.upload", control.id, "Load test " + System.nanoTime() + ".pdf", uploadContent, reason,
                Session.ok2xx());
    }

    /** Create Control (the next cycle) of a Monthly or Quarterly control nobody has made it of yet. */
    private void createControl(Session session, Random random) {
        Control control = Data.any(data.controls, c -> !c.nextCycleMade && !c.draft() && c.active()
                && ("Monthly".equals(c.frequency) || "Quarterly".equals(c.frequency)), random);
        if (control == null || !control.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            control.nextCycleMade = true;
            session.getJson("create.preview", "/api/controls/" + control.id + "/next-cycle");
            session.postJson("create.post", "/api/controls/" + control.id + "/next-cycle", "{}",
                    Session.okOrAllowed(409));
        } finally {
            control.busy.set(false);
        }
    }
}
