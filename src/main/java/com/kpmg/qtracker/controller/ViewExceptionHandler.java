package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.exception.ControlNotAvailableException;
import com.kpmg.qtracker.exception.ForbiddenException;
import com.kpmg.qtracker.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

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

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGeneric(Exception ex, HttpServletRequest request) {
        return build("error/500", HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", ex.getMessage(), request);
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
