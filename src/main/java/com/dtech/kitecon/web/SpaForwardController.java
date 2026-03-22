package com.dtech.kitecon.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards all non-API, non-static GET requests to index.html so that
 * React Router can handle client-side routing.
 *
 * Without this, navigating directly to /dashboard or /copilot would return
 * 404 (no backend handler) and never reach the React app.
 */
@Controller
public class SpaForwardController {

    /**
     * Single-segment paths: /dashboard, /chart, /copilot, /skills, etc.
     * Regex excludes paths with a dot (static files like .js, .css, .png).
     */
    @GetMapping(value = "/{path:^(?!api|ws|swagger-ui|v3)(?!.*\\.)[^\\.]*$}")
    public String forwardSingleSegment() {
        return "forward:/index.html";
    }

    /**
     * Two-segment paths: /screener/123, /trades/456, etc.
     */
    @GetMapping(value = "/{seg1:^(?!api|ws).*$}/{seg2:[^\\.]*}")
    public String forwardTwoSegment() {
        return "forward:/index.html";
    }
}
