package com.dtech.kitecon.service;

import com.dtech.kitecon.data.TagValidationCache;
import com.dtech.kitecon.repository.TagValidationCacheRepository;
import com.dtech.kitecon.service.ai.AIProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * TagValidationService - Validates and normalizes pattern tags using AI
 * Prevents duplicate tags due to typos and maintains consistent naming
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TagValidationService {

    private final TagValidationCacheRepository cacheRepository;
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;

    private ChatClient chatClient;

    /**
     * Validate and normalize a list of tags
     * - Checks cache first
     * - Uses AI for new/unknown tags
     * - Removes duplicates after normalization
     */
    @Transactional
    public List<String> validateAndNormalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        // Remove empty/blank tags
        List<String> cleanTags = tags.stream()
            .filter(t -> t != null && !t.trim().isEmpty())
            .map(String::trim)
            .collect(Collectors.toList());

        if (cleanTags.isEmpty()) {
            return List.of();
        }

        List<String> normalizedTags = new ArrayList<>();
        List<String> needsAIValidation = new ArrayList<>();

        // Step 1: Check cache for each tag
        for (String tag : cleanTags) {
            Optional<TagValidationCache> cached = cacheRepository.findByOriginalTagIgnoreCase(tag);

            if (cached.isPresent()) {
                // Found in cache
                normalizedTags.add(cached.get().getNormalizedTag());
                // Increment usage count
                cacheRepository.incrementValidationCount(tag.toLowerCase());
                log.debug("Tag '{}' found in cache: '{}'", tag, cached.get().getNormalizedTag());
            } else {
                // Need AI validation
                needsAIValidation.add(tag);
            }
        }

        // Step 2: Validate unknown tags with AI
        if (!needsAIValidation.isEmpty()) {
            try {
                Map<String, String> aiValidatedTags = validateTagsWithAI(needsAIValidation);

                // Save to cache and add to results
                for (Map.Entry<String, String> entry : aiValidatedTags.entrySet()) {
                    String original = entry.getKey();
                    String normalized = entry.getValue();

                    // Save to cache
                    TagValidationCache cacheEntry = TagValidationCache.builder()
                        .originalTag(original.toLowerCase())
                        .normalizedTag(normalized)
                        .validationCount(1)
                        .confidence(0.95)
                        .build();
                    cacheRepository.save(cacheEntry);

                    normalizedTags.add(normalized);
                    log.info("AI validated tag: '{}' → '{}'", original, normalized);
                }
            } catch (Exception e) {
                log.error("Error validating tags with AI", e);
                // Fallback: use original tags with basic normalization
                for (String tag : needsAIValidation) {
                    normalizedTags.add(basicNormalize(tag));
                }
            }
        }

        // Step 3: Remove duplicates (case-insensitive)
        Set<String> uniqueTags = new LinkedHashSet<>(normalizedTags);

        return new ArrayList<>(uniqueTags);
    }

    /**
     * Validate a single tag
     */
    @Transactional
    public String validateTag(String tag) {
        List<String> validated = validateAndNormalizeTags(List.of(tag));
        return validated.isEmpty() ? basicNormalize(tag) : validated.get(0);
    }

    /**
     * Get popular/common tags for suggestions
     */
    public List<String> getPopularTags() {
        return cacheRepository.findTop50ByOrderByValidationCountDesc()
            .stream()
            .map(TagValidationCache::getNormalizedTag)
            .distinct()
            .limit(30)
            .collect(Collectors.toList());
    }

    /**
     * Use AI to validate and normalize tags
     */
    private Map<String, String> validateTagsWithAI(List<String> tags) {
        if (chatClient == null) {
            chatClient = chatClientBuilder.build();
        }

        String prompt = buildValidationPrompt(tags);

        try {
            String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

            return parseAIResponse(response, tags);

        } catch (Exception e) {
            log.error("Error calling AI for tag validation", e);
            // Fallback: basic normalization
            Map<String, String> fallback = new HashMap<>();
            for (String tag : tags) {
                fallback.put(tag, basicNormalize(tag));
            }
            return fallback;
        }
    }

    /**
     * Build prompt for AI tag validation
     */
    private String buildValidationPrompt(List<String> tags) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a technical analysis expert. Validate and correct these pattern/indicator tags.\n\n");
        prompt.append("Rules:\n");
        prompt.append("1. Fix any typos or misspellings\n");
        prompt.append("2. Use standard technical analysis terminology\n");
        prompt.append("3. Use proper capitalization (Title Case)\n");
        prompt.append("4. Keep tags concise but descriptive\n");
        prompt.append("5. If a tag is completely invalid/nonsensical, suggest the closest valid pattern name\n\n");

        prompt.append("Tags to validate:\n");
        for (int i = 0; i < tags.size(); i++) {
            prompt.append((i + 1)).append(". ").append(tags.get(i)).append("\n");
        }

        prompt.append("\nRespond with JSON array format:\n");
        prompt.append("[{\"original\": \"tag1\", \"normalized\": \"Corrected Tag1\"}, ...]\n\n");
        prompt.append("Examples:\n");
        prompt.append("- \"head and sholder\" → \"Head and Shoulders\"\n");
        prompt.append("- \"tripple top\" → \"Triple Top\"\n");
        prompt.append("- \"suport resistence\" → \"Support/Resistance\"\n");
        prompt.append("- \"breakout\" → \"Breakout\"\n");

        return prompt.toString();
    }

    /**
     * Parse AI response to extract tag mappings
     */
    private Map<String, String> parseAIResponse(String response, List<String> originalTags) {
        Map<String, String> result = new HashMap<>();

        try {
            // Try to extract JSON array from response
            String jsonContent = response;

            // Extract JSON if wrapped in markdown code blocks
            if (response.contains("```")) {
                int start = response.indexOf("[");
                int end = response.lastIndexOf("]") + 1;
                if (start >= 0 && end > start) {
                    jsonContent = response.substring(start, end);
                }
            }

            // Parse JSON
            List<Map<String, String>> mappings = objectMapper.readValue(
                jsonContent,
                new TypeReference<List<Map<String, String>>>() {}
            );

            for (Map<String, String> mapping : mappings) {
                String original = mapping.get("original");
                String normalized = mapping.get("normalized");
                if (original != null && normalized != null) {
                    result.put(original, normalized);
                }
            }

            // Fallback for any missing tags
            for (String tag : originalTags) {
                if (!result.containsKey(tag)) {
                    result.put(tag, basicNormalize(tag));
                }
            }

        } catch (Exception e) {
            log.error("Error parsing AI response for tag validation", e);
            // Fallback: basic normalization
            for (String tag : originalTags) {
                result.put(tag, basicNormalize(tag));
            }
        }

        return result;
    }

    /**
     * Basic normalization without AI (fallback)
     * Capitalizes first letter of each word
     */
    private String basicNormalize(String tag) {
        if (tag == null || tag.trim().isEmpty()) {
            return tag;
        }

        String trimmed = tag.trim();
        String[] words = trimmed.split("\\s+");

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                // Special handling for abbreviations
                if (word.toUpperCase().equals(word) && word.length() <= 3) {
                    result.append(word.toUpperCase());
                } else {
                    result.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                }
            }
        }

        return result.toString();
    }
}
