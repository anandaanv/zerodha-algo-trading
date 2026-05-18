package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.data.copilot.UserAiProvider;
import com.dtech.kitecon.repository.copilot.UserAiProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserAiProviderService {

    private final UserAiProviderRepository providerRepository;
    private final CopilotEncryptionService encryptionService;

    public List<UserAiProvider> listProviders(Long userId) {
        return providerRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    @Transactional
    public UserAiProvider createProvider(Long userId, String name, String baseUrl, String model,
                                          String apiKey, boolean localProvider, boolean makeActive) {
        String encrypted = (apiKey != null && !apiKey.isBlank()) ? encryptionService.encrypt(apiKey) : null;

        if (makeActive) {
            providerRepository.deactivateAll(userId);
        }

        // If this is the first provider, make it active automatically
        boolean firstProvider = !providerRepository.existsByUserId(userId);

        return providerRepository.save(UserAiProvider.builder()
                .userId(userId)
                .name(name)
                .baseUrl(baseUrl)
                .model(model)
                .apiKeyEncrypted(encrypted)
                .localProvider(localProvider)
                .active(makeActive || firstProvider)
                .build());
    }

    @Transactional
    public UserAiProvider updateProvider(Long userId, Long id, String name, String baseUrl, String model,
                                          String apiKey, boolean localProvider) {
        UserAiProvider p = providerRepository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + id));
        p.setName(name);
        p.setBaseUrl(baseUrl);
        p.setModel(model);
        p.setLocalProvider(localProvider);
        if (apiKey != null && !apiKey.isBlank()) {
            p.setApiKeyEncrypted(encryptionService.encrypt(apiKey));
        }
        return providerRepository.save(p);
    }

    @Transactional
    public void deleteProvider(Long userId, Long id) {
        UserAiProvider p = providerRepository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + id));
        boolean wasActive = p.isActive();
        providerRepository.delete(p);

        // If deleted provider was active, activate the next available one
        if (wasActive) {
            providerRepository.findByUserIdOrderByCreatedAtAsc(userId)
                    .stream().findFirst().ifPresent(next -> {
                        next.setActive(true);
                        providerRepository.save(next);
                    });
        }
    }

    @Transactional
    public UserAiProvider setActive(Long userId, Long id) {
        providerRepository.deactivateAll(userId);
        UserAiProvider p = providerRepository.findByUserIdAndId(userId, id)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + id));
        p.setActive(true);
        return providerRepository.save(p);
    }

    public Optional<UserAiProvider> getActiveProvider(Long userId) {
        return providerRepository.findByUserIdAndActiveTrue(userId);
    }

    /**
     * Finds the first provider for this user whose base URL contains the substring (e.g. "anthropic.com"),
     * regardless of active flag. Used by per-role provider routing (Agent 1 wants Anthropic even when the
     * user's default active provider is something else).
     */
    public Optional<UserAiProvider> findByUserIdAndBaseUrlContains(Long userId, String substr) {
        if (substr == null || substr.isBlank()) return Optional.empty();
        return listProviders(userId).stream()
                .filter(p -> p.getBaseUrl() != null && p.getBaseUrl().contains(substr))
                .findFirst();
    }

    /** Returns the decrypted key for a specific provider entity. */
    public Optional<String> getDecryptedApiKey(UserAiProvider provider) {
        if (provider == null) return Optional.empty();
        String enc = provider.getApiKeyEncrypted();
        if (enc == null || enc.isBlank()) return Optional.empty();
        return Optional.of(encryptionService.decrypt(enc));
    }

    /** Returns the decrypted API key for the active provider, or empty. */
    public Optional<String> getActiveApiKey(Long userId) {
        return getActiveProvider(userId)
                .map(UserAiProvider::getApiKeyEncrypted)
                .filter(k -> k != null && !k.isBlank())
                .map(encryptionService::decrypt);
    }

    public boolean hasAnyProvider(Long userId) {
        return providerRepository.existsByUserId(userId);
    }

    /** DTO for returning provider info to the frontend (never exposes the encrypted key). */
    public record ProviderDto(Long id, String name, String baseUrl, String model,
                               boolean localProvider, boolean active, boolean hasApiKey) {}

    public List<ProviderDto> listProviderDtos(Long userId) {
        return listProviders(userId).stream()
                .map(p -> new ProviderDto(
                        p.getId(), p.getName(), p.getBaseUrl(), p.getModel(),
                        p.isLocalProvider(), p.isActive(),
                        p.getApiKeyEncrypted() != null && !p.getApiKeyEncrypted().isBlank()))
                .toList();
    }
}
