package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlAttachmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.DeadlineOverdue;
import com.kpmg.qtracker.service.FileStorageService;
import com.kpmg.qtracker.service.SoqmYear;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The pre-release test pass (ReleaseScenario*IT): one application on its own H2 database with the dev security
 * chain (form login, session, CSRF, the read-only check), made-up people signing in with a password, and controls
 * built for each test. Every name is unique per run, so the classes share the context and run in any order;
 * every request comes from its own address, so RateLimitingFilter (per address) never counts across tests.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:release-scenario-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "reminders.enabled=false",
        "controls.auto-create.enabled=false",
        "notifications.email.enabled=false",
        "file.upload.dir=target/it-uploads-release"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "dev"})
abstract class ReleaseScenarioSupport {

    static final String PASSWORD = "Release#2026";
    /** Unique per run: the shared H2 database keeps the people and controls of earlier classes. */
    static final String RUN = UUID.randomUUID().toString().substring(0, 6);

    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final AtomicInteger ADDRESS = new AtomicInteger();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    UserRepository userRepository;
    @Autowired
    ControlRepository controlRepository;
    @Autowired
    ControlAssignmentRepository assignmentRepository;
    @Autowired
    ControlDetailsRepository detailsRepository;
    @Autowired
    ControlAttachmentRepository attachmentRepository;
    @Autowired
    NotificationRepository notificationRepository;
    @Autowired
    WorkflowHistoryRepository historyRepository;
    @Autowired
    AdminAuditLogRepository auditRepository;
    @Autowired
    FileStorageService fileStorageService;

    static int next() {
        return COUNT.incrementAndGet();
    }

    static LocalDate today() {
        return DeadlineOverdue.today(Instant.now());
    }

    // ---- people ----

    User soqm(String tag) {
        return person(tag, AccessLevel.SOQM, AccessScope.ALL);
    }

    User myEdit(String tag) {
        return person(tag, AccessLevel.PARTICIPANT, AccessScope.OWN);
    }

    User allEdit(String tag) {
        return person(tag, AccessLevel.PARTICIPANT, AccessScope.ALL);
    }

    User myRead(String tag) {
        return person(tag, AccessLevel.READ_ONLY, AccessScope.OWN);
    }

    User allRead(String tag) {
        return person(tag, AccessLevel.READ_ONLY, AccessScope.ALL);
    }

    User kdn(String tag) {
        return person(tag, AccessLevel.READ_ONLY, AccessScope.KDN);
    }

    /** A made-up person, signing in with PASSWORD; the address is rs-tag-n-run@release.test. */
    User person(String tag, AccessLevel level, AccessScope scope) {
        int n = next();
        User user = new User();
        user.setMail(("rs-" + tag + "-" + n + "-" + RUN + "@release.test").toLowerCase(Locale.ROOT));
        user.setDisplayName("Rs " + tag + " " + n);
        user.setAccessLevel(level);
        user.setAccessScope(scope);
        user.setAdminAccess(level == AccessLevel.SOQM);
        user.setEnabled(true);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        return userRepository.save(user);
    }

    User disable(User user) {
        User stored = userRepository.findById(user.getId()).orElseThrow();
        stored.setEnabled(false);
        return userRepository.save(stored);
    }

    // ---- sessions and requests ----

    /** Signs in through the real form; the session of the signed-in person. */
    MockHttpSession login(User user) throws Exception {
        MvcResult result = signIn(user.getMail(), PASSWORD);
        assertThat(result.getResponse().getRedirectedUrl()).as("login of %s", user.getMail()).isEqualTo("/");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    MvcResult signIn(String login, String password) throws Exception {
        return mockMvc.perform(post("/login").with(csrf()).with(ownAddress())
                        .param("username", login)
                        .param("password", password))
                .andReturn();
    }

    /** A request in the session with a valid CSRF header (as js/csrf.js sends it), from its own address. */
    MvcResult call(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        request.with(ownAddress()).with(csrf().asHeader());
        if (session != null) {
            request.session(session);
        }
        return mockMvc.perform(request).andReturn();
    }

    MvcResult callJson(MockHttpSession session, MockHttpServletRequestBuilder request, String json) throws Exception {
        return call(session, request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    int status(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        return call(session, request).getResponse().getStatus();
    }

    JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** "login" when the answer sends the browser to the sign-in page, else the status code. */
    static String outcome(MvcResult result) {
        int status = result.getResponse().getStatus();
        String location = result.getResponse().getRedirectedUrl();
        String forwarded = result.getResponse().getForwardedUrl();
        if ((status == 302 && location != null && location.contains("/login"))
                || (forwarded != null && forwarded.startsWith("/login"))) {
            return "login";
        }
        return String.valueOf(status);
    }

    /** Every request from its own address: RateLimitingFilter counts per address. */
    static RequestPostProcessor ownAddress() {
        int n = ADDRESS.incrementAndGet();
        String address = "10.77." + (n / 250 % 250) + "." + (1 + n % 250);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    static RequestPostProcessor address(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    // ---- controls ----

    /** People of a control; null fields stay empty. */
    static final class People {
        String facilitator;
        String operator;
        String soqm;
        String owner;
        String shared;
        User creator;

        People facilitator(User... users) {
            facilitator = mails(users);
            return this;
        }

        People operator(User... users) {
            operator = mails(users);
            return this;
        }

        People soqm(User... users) {
            soqm = mails(users);
            return this;
        }

        People owner(User... users) {
            owner = mails(users);
            return this;
        }

        People shared(User... users) {
            shared = mails(users);
            return this;
        }

        People creator(User user) {
            creator = user;
            return this;
        }

        private static String mails(User... users) {
            StringBuilder out = new StringBuilder();
            for (User user : users) {
                if (!out.isEmpty()) {
                    out.append(',');
                }
                out.append(user.getMail());
            }
            return out.toString();
        }
    }

    static People people() {
        return new People();
    }

    /** A control with the given ID (made unique per run), status and people, one attachment on Details. */
    Control control(String idPrefix, String status, People people) throws Exception {
        return control(idPrefix, status, people, control -> { });
    }

    Control control(String idPrefix, String status, People people, Consumer<Control> extra) throws Exception {
        Control control = new Control();
        control.setControlId(idPrefix + "-" + next() + "-" + RUN.toUpperCase(Locale.ROOT));
        control.setControlFrequency("Monthly");
        control.setControlCategory("Manual");
        control.setControlType("Preventive");
        control.setComponent("HR");
        control.setOperatedBy("Finance");
        control.setPriority("High");
        control.setNonAuditServicesApplicability("No");
        control.setControlStatus("ACTIVE");
        control.setPerformanceStatus(status);
        control.setControlDescription("Release test control");
        control.setPrp("PRP");
        control.setSoqmYear(SoqmYear.current(today()));
        control.setCreatedBy(people.creator);
        control.setCreatedAt(LocalDateTime.now());
        control.setAttachmentDetailsPath(fileStorageService.saveFile(new MockMultipartFile("file", "evidence.pdf",
                "application/pdf", "%PDF-1.4 evidence".getBytes(StandardCharsets.UTF_8)), control.getControlId()));
        extra.accept(control);
        control = controlRepository.save(control);

        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setFacilitator(people.facilitator);
        assignment.setControlOperator(people.operator);
        assignment.setSoqmLead(people.soqm);
        assignment.setProcessOwner(people.owner);
        assignment.setControlSharedWith(people.shared);
        assignment.setControlOperationDate(today().plusDays(3));
        assignment.setControlOperationDeadline(today().plusDays(17));
        assignment.setNextControlOperationDate(today().plusDays(3).plusMonths(1));
        assignmentRepository.save(assignment);

        ControlDetails details = detailsRepository.findByControlId(control.getId()).orElseThrow();
        details.setProcessActivities("Activities");
        details.setControlStepsPerformed("Steps performed");
        details.setSoqmHeadComments("SoQM comments");
        details.setProcessOwnerComments("Owner comments");
        detailsRepository.save(details);
        return controlRepository.findById(control.getId()).orElseThrow();
    }

    Control reload(Control control) {
        return controlRepository.findById(control.getId()).orElseThrow();
    }
}
