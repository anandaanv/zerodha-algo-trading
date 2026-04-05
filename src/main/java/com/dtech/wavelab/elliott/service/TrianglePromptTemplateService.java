package com.dtech.wavelab.elliott.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class TrianglePromptTemplateService {
    // Phase 2: move templates to DB-backed prompt/workflow editing with file fallback.

    private final ResourceLoader resourceLoader;

    public TrianglePromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String templateName, String version) {
        String path = "classpath:prompts/wavelab/triangles/" + templateName + "_" + version + ".txt";
        Resource resource = resourceLoader.getResource(path);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + path, e);
        }
    }

    public String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }
}
