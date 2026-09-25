package com.hytechster.ewallet.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns exceptions into friendly pages. Never shows stack traces or SQL. By the time we get here
 * the money transaction has already rolled back, so "nothing was charged" is always true.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException ex, HttpServletRequest request, HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.UNPROCESSABLE_ENTITY.value());
        model.addAttribute("heading", titleFor(ex));
        model.addAttribute("message", ex.getMessage());
        model.addAttribute("backUrl", backUrl(request));
        return "result/failed";
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String conflict(DataIntegrityViolationException ex, HttpServletRequest request, HttpServletResponse response,
                           Model model) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        response.setStatus(HttpStatus.CONFLICT.value());
        model.addAttribute("heading", "That didn't go through");
        model.addAttribute("message",
                "Something changed while we were working on it. Check your history before trying again.");
        model.addAttribute("backUrl", backUrl(request));
        return "result/failed";
    }

    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    public String notFound(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
        return "error/404";
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public String badRequest(HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        model.addAttribute("status", 400);
        return "error";
    }

    @ExceptionHandler(AccessDeniedException.class)
    public void accessDenied(AccessDeniedException ex) {
        throw ex; // let Spring Security handle it
    }

    @ExceptionHandler(Exception.class)
    public String unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response, Model model) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), ex);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        model.addAttribute("status", 500);
        return "error";
    }

    private static String titleFor(BusinessException ex) {
        return switch (ex) {
            case InsufficientFundsException e -> "Not enough balance";
            case AccountFrozenException e -> "Wallet frozen";
            case LimitExceededException e -> "Over the limit";
            default -> "That didn't go through";
        };
    }

    /** Where "Try again" goes: the form page for the action that failed. */
    private static String backUrl(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return "/";
        }
        String uri = request.getRequestURI().substring(request.getContextPath().length());
        if (uri.matches("/admin/users/\\d+/(freeze|unfreeze)")) {
            return "/admin/users";
        }
        return uri.replaceFirst("/(reverse/review|reverse|review)$", "");
    }
}
