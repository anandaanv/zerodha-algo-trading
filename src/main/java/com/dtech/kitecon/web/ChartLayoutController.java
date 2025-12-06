package com.dtech.kitecon.web;

import com.dtech.kitecon.data.ChartLayout;
import com.dtech.kitecon.repository.ChartLayoutRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/layouts")
@CrossOrigin
public class ChartLayoutController {

    private final ChartLayoutRepository layoutRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ResponseEntity<List<ChartLayout>> getAllLayouts(Authentication auth) {
        String username = auth.getName();
        return ResponseEntity.ok(layoutRepository.findByUsernameOrderByUpdatedAtDesc(username));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChartLayout> getLayout(@PathVariable Long id, Authentication auth) {
        return layoutRepository.findByIdAndUsername(id, auth.getName())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ChartLayout> saveLayout(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            String name = (String) body.get("name");
            Object contentObj = body.get("content");
            
            if (name == null || contentObj == null) {
                return ResponseEntity.badRequest().build();
            }

            String content = objectMapper.writeValueAsString(contentObj);
            String username = auth.getName();

            ChartLayout layout = layoutRepository.findByNameAndUsername(name, username)
                    .orElse(ChartLayout.builder()
                            .name(name)
                            .username(username)
                            .build());

            layout.setLayoutContent(content);
            return ResponseEntity.ok(layoutRepository.save(layout));
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteLayout(@PathVariable Long id, Authentication auth) {
        return layoutRepository.findByIdAndUsername(id, auth.getName())
                .map(layout -> {
                    layoutRepository.delete(layout);
                    return ResponseEntity.ok(Map.of("status", "deleted"));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
