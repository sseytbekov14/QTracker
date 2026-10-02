package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.exception.ControlNotAvailableException;
import com.kpmg.qtracker.exception.ForbiddenException;
import com.kpmg.qtracker.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
@ControllerAdvice(assignableTypes = ViewController.class)
public class ViewExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ModelAndView handleForbidden(ForbiddenException ex, HttpServletRequest request) {
        return build("error/403", HttpStatus.FORBIDDEN, "Access denied", ex.getMessage(), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ModelAndView handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build("error/404", HttpStatus.NOT_FOUND, "Page not found", ex.getMessage(), request);
    }

    @ExceptionHandler(ControlNotAvailableException.class)
    public ModelAndView handleControlNotAvailable(ControlNotAvailableException ex, HttpServletRequest request) {
        return build("control-not-available", HttpStatus.OK, "Control Not Available Yet", ex.getMessage(), request);
    }

    /**
     * The page shows only a general text; what went wrong goes to the log with the request's correlationId
     * (CorrelationIdFilter), written into the message because the log pattern does not print the MDC.
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {} {} (correlationId={})",
                request != null ? request.getMethod() : "-",
                request != null ? request.getRequestURI() : "-",
                MDC.get("correlationId"), ex);
        ModelAndView mav = new ModelAndView("error/500");
        mav.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        return mav;
    }

    private ModelAndView build(String viewName,
                               HttpStatus status,
                               String title,
                               String message,
                               HttpServletRequest request) {
        ModelAndView mav = new ModelAndView(viewName);
        mav.setStatus(status);
        mav.addObject("title", title);
        mav.addObject("message", message);
        mav.addObject("path", request != null ? request.getRequestURI() : "");
        return mav;
    }
}
