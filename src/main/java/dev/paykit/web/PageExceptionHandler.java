package dev.paykit.web;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/** HTML error page for the storefront and demo pages (the JSON API uses problem+json). */
@ControllerAdvice(assignableTypes = {PageController.class, dev.paykit.demo.DemoController.class})
public class PageExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(NotFoundException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    String conflict(ConflictException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "error-page";
    }
}
