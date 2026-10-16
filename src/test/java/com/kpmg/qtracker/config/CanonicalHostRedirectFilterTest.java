package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalHostRedirectFilterTest {

    private static final String SITE = "kzqtracker.go1.kworld.kpmg.com";

    @Test
    void shortHostIsSentToTheSiteWithPathAndQuery() throws Exception {
        MockHttpServletRequest request = request("kzqtracker", "/saml2/authenticate/entra");
        request.setQueryString("a=1&b=two");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new CanonicalHostRedirectFilter(SITE).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(301);
        assertThat(response.getHeader("Location"))
                .isEqualTo("https://" + SITE + "/saml2/authenticate/entra?a=1&b=two");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rootWithoutQueryIsSentToTheSiteRoot() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new CanonicalHostRedirectFilter(SITE).doFilter(request("kzqtracker", "/"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(301);
        assertThat(response.getHeader("Location")).isEqualTo("https://" + SITE + "/");
    }

    @Test
    void theSiteItselfPassesWhateverTheCase() throws Exception {
        assertPasses(new CanonicalHostRedirectFilter(SITE), "KZQTracker.go1.kworld.kpmg.com");
        assertPasses(new CanonicalHostRedirectFilter(" KZQTRACKER.go1.kworld.kpmg.com "), SITE);
    }

    @Test
    void localhostIsNeverRedirected() throws Exception {
        assertPasses(new CanonicalHostRedirectFilter(SITE), "localhost");
        assertPasses(new CanonicalHostRedirectFilter(SITE), "127.0.0.1");
    }

    @Test
    void emptyPropertyTurnsTheRedirectOff() throws Exception {
        assertPasses(new CanonicalHostRedirectFilter(""), "kzqtracker");
        assertPasses(new CanonicalHostRedirectFilter("  "), "kzqtracker");
        assertPasses(new CanonicalHostRedirectFilter(null), "kzqtracker");
    }

    private static void assertPasses(CanonicalHostRedirectFilter filter, String host) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request(host, "/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Location")).isNull();
        assertThat(chain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String host, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setServerName(host);
        return request;
    }
}
