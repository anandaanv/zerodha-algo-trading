package com.dtech.drawingscan.web;

import com.dtech.drawingscan.dto.DrawingScanRequest;
import com.dtech.drawingscan.dto.DrawingScanResponse;
import com.dtech.drawingscan.service.DrawingScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/drawing-scan")
@CrossOrigin
public class DrawingScanController {

    private final DrawingScanService drawingScanService;

    @PostMapping("/run")
    public ResponseEntity<DrawingScanResponse> runScan(
        @RequestBody DrawingScanRequest request,
        Authentication auth
    ) {
        String username = auth != null ? auth.getName() : "anonymous";
        DrawingScanResponse response = drawingScanService.scan(request, username);
        return ResponseEntity.ok(response);
    }
}
