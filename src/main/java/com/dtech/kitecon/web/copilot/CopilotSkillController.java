package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.service.copilot.CopilotSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/copilot/skills")
@RequiredArgsConstructor
public class CopilotSkillController {

    private final CopilotSkillService skillService;
    private final UserRepository userRepository;

    @GetMapping
    public ResponseEntity<List<CopilotSkill>> getAllSkills(Authentication auth) {
        return ResponseEntity.ok(skillService.getAllSkillsForUser(resolveUserId(auth)));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<CopilotSkill>> getByCategory(Authentication auth,
                                                              @PathVariable String category) {
        return ResponseEntity.ok(skillService.getSkillsByCategory(resolveUserId(auth), category));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CopilotSkill> getSkill(@PathVariable Long id) {
        return skillService.getSkillById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CopilotSkill> createSkill(Authentication auth,
                                                      @RequestBody CopilotSkill skill) {
        skill.setUserId(resolveUserId(auth));
        skill.setIsSystemSeed(false);
        return ResponseEntity.ok(skillService.saveSkill(skill));
    }

    @PutMapping("/{id}")
    public ResponseEntity<CopilotSkill> updateSkill(@PathVariable Long id,
                                                     @RequestBody CopilotSkill skill) {
        return skillService.getSkillById(id)
                .map(existing -> {
                    skill.setId(id);
                    skill.setUserId(existing.getUserId());
                    return ResponseEntity.ok(skillService.saveSkill(skill));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable Long id) {
        skillService.deleteSkill(id);
        return ResponseEntity.ok(Map.of("status", "deactivated"));
    }

    /** Seed demo skills (Triangle + Wave 4) for the current user */
    @PostMapping("/seed")
    public ResponseEntity<?> seedDemoSkills(Authentication auth) {
        skillService.seedDemoSkillsForUser(resolveUserId(auth));
        return ResponseEntity.ok(Map.of("status", "seeded", "skills", List.of("triangle", "wave_4")));
    }

    /** Get a full prompt-ready representation of a skill (for preview in Skill Builder UI) */
    @GetMapping("/{id}/prompt")
    public ResponseEntity<Map<String, String>> getSkillPrompt(@PathVariable Long id) {
        return skillService.getSkillById(id)
                .map(skill -> ResponseEntity.ok(Map.of("prompt", skillService.buildSkillPrompt(skill))))
                .orElse(ResponseEntity.notFound().build());
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
