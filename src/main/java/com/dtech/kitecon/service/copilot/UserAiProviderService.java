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
