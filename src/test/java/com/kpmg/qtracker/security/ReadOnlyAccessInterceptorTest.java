package com.kpmg.qtracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The general server check: a read-only user changes nothing under /api/**. */
class ReadOnlyAccessInterceptorTest {

    private final ReadOnlyAccessInterceptor interceptor = new ReadOnlyAccessInterceptor(new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "PUT,    /api/controls/7",
            "POST,   /api/controls",
            "POST,   /api/control-details",
            "POST,   /api/control-assignment",
            "POST,   /api/workflow/submit-to-control-operator",
            "POST,   /api/workflow/return-to-facilitator",
            "POST,   /api/attachments/upload/7",
            "DELETE, /api/attachments/delete/7",
            "POST,   /api/controls/7/rename-id",
            "POST,   /api/users/3/access",
            "PUT,    /api/admin/users/3/email",
    })
    void readOnlyUser_isRefusedOnEveryWrite(String method, String path) throws Exception {
        MDC.put("correlationId", "cid-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request(method, path, readOnly(true)), response, new Object());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"READ_ONLY\"")
                .contains("\"message\":\"You have read-only access: you can view and download, but not change anything.\"")
                .contains("\"correlationId\":\"cid-1\"");
    }

    @Test
    void readOnlyUser_stillReads_andMarksOwnNotificationsAsRead() throws Exception {
        assertThat(interceptor.preHandle(request("GET", "/api/control-details", readOnly(false)),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("POST", "/notifications/5/read", readOnly(false)),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void writers_andRequestsWithoutAUser_passThrough() throws Exception {
        User participant = TestUsers.user("p@kpmg.kz", AccessLevel.PARTICIPANT, AccessScope.OWN, false);
        User soqm = TestUsers.user("s@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, false);

        assertThat(interceptor.preHandle(request("POST", "/api/control-details", participant),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("PUT", "/api/controls/1", soqm),
                new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(interceptor.preHandle(request("POST", "/api/controls", null),
                new MockHttpServletResponse(), new Object())).isTrue();
    }

    private static User readOnly(boolean admin) {
        return TestUsers.user("ro@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.ALL, admin);
    }

    private static MockHttpServletRequest request(String method, String path, User user) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (user != null) {
            request.getSession(true).setAttribute("currentUser", user);
        }
        return request;
    }
}
