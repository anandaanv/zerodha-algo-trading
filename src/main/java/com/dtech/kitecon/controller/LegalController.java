package com.dtech.kitecon.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for serving legal pages (Privacy Policy, Terms of Service)
 * Required for Google OAuth verification
 */
@Controller
public class LegalController {

    @GetMapping("/privacy-policy")
    public String privacyPolicy() {
        return "forward:/privacy-policy.html";
    }

    @GetMapping("/terms-of-service")
    public String termsOfService() {
        return "forward:/terms-of-service.html";
    }

    @GetMapping("/privacy")
    public String privacy() {
        return "forward:/privacy-policy.html";
    }

    @GetMapping("/terms")
    public String terms() {
        return "forward:/terms-of-service.html";
    }
}
