package com.kpmg.qtracker.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UrlPathHelper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;

/**
 * A page address with a value of the wrong kind, typed or cut by hand (2026-11-10): "?page=abc", "?size=x",
 * "?from=31-12". A parameter of a page (GET) is left out and the page opens with its default - the first page, the
 * usual period - instead of the Admin Panel's "Whitelabel Error Page" (400) and the lists' error page with an ERROR in
 * the log (500). A wrong id in the path ("/view-control/abc") is a page that does not exist (404). Before
 * {@link ViewExceptionHandler}, which would take it as an unexpected error.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice(assignableTypes = {ViewController.class, AdminViewController.class})
public class BadPageAddressHandler {

    private static final UrlPathHelper PATHS = new UrlPathHelper();

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ModelAndView handleWrongValue(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        boolean inPath = ex.getParameter().hasParameterAnnotation(PathVariable.class);
        if (!inPath && "GET".equalsIgnoreCase(request.getMethod())) {
            String target = withoutParameter(PATHS.getPathWithinApplication(request), request.getQueryString(),
                    ex.getName());
            if (target != null) {
                log.debug("Page {}: the value of \"{}\" is not of its kind, opened without it", request.getRequestURI(),
                        ex.getName());
                // As written: no {...} expanded, nothing encoded again (the query is passed on as it came)
                RedirectView redirect = new RedirectView(target, true);
                redirect.setExpandUriTemplateVariables(false);
                return new ModelAndView(redirect);
            }
        }
        log.debug("Page {}: the value of \"{}\" is not of its kind", request.getRequestURI(), ex.getName());
        ModelAndView mav = new ModelAndView("error/404");
        mav.setStatus(HttpStatus.NOT_FOUND);
        mav.addObject("title", "Page not found");
        mav.addObject("message", "The address is not complete or not correct.");
        return mav;
    }

    /**
     * The address without the parameter (every value of it); the other parameters as they were. Null for a path that
     * is not one of the application's own ("//host", no leading slash), so the answer never leads elsewhere.
     */
    static String withoutParameter(String path, String query, String name) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.contains("\\")) {
            return null;
        }
        StringJoiner kept = new StringJoiner("&");
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int at = pair.indexOf('=');
                String key = at < 0 ? pair : pair.substring(0, at);
                if (!name.equals(decode(key))) {
                    kept.add(pair);
                }
            }
        }
        return kept.length() == 0 ? path : path + "?" + kept;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
