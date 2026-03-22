package com.dtech.kitecon.service.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.data.copilot.UserOpenAiCredential;
import com.dtech.kitecon.repository.copilot.UserOpenAiCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserOpenAiCredentialService {

    private final UserOpenAiCredentialRepository credentialRepository;
    private final CopilotEncryptionService encryptionService;

    /**
     * Save or update the user's OpenAI API key.
     * The key is encrypted before storage.
     */
    @Transactional
    public void saveCredential(Long userId, String apiKey, String model, String baseUrl) {
        String encrypted = encryptionService.encrypt(apiKey);

        Optional<UserOpenAiCredential> existing = credentialRepository.findByUserId(userId);
        if (existing.isPresent()) {
            UserOpenAiCredential cred = existing.get();
            cred.setApiKeyEncrypted(encrypted);
            if (model != null) cred.setModel(model);
            if (baseUrl != null) cred.setBaseUrl(baseUrl);
            credentialRepository.save(cred);
        } else {
            credentialRepository.save(UserOpenAiCredential.builder()
                    .userId(userId)
                    .apiKeyEncrypted(encrypted)
                    .model(model != null ? model : "gpt-4o-mini")
                    .baseUrl(baseUrl != null ? baseUrl : "https://api.openai.com/v1")
                    .build());
        }
    }

    /** Returns the decrypted API key for a user, or empty if not configured. */
    public Optional<String> getApiKey(Long userId) {
        return credentialRepository.findByUserId(userId)
                .map(c -> encryptionService.decrypt(c.getApiKeyEncrypted()));
    }

    /** Returns the full credential record (without decrypting the key). */
    public Optional<UserOpenAiCredential> getCredential(Long userId) {
        return credentialRepository.findByUserId(userId);
    }

    /** Returns the model preference for a user. */
    public String getModel(Long userId) {
        return credentialRepository.findByUserId(userId)
                .map(UserOpenAiCredential::getModel)
                .orElse("gpt-4o-mini");
    }

    /** Returns the base URL preference for a user. */
    public String getBaseUrl(Long userId) {
        return credentialRepository.findByUserId(userId)
                .map(UserOpenAiCredential::getBaseUrl)
                .orElse("https://api.openai.com/v1");
    }

    /** True if the user has configured their own API key. */
    public boolean hasCredential(Long userId) {
        return credentialRepository.existsByUserId(userId);
    }

    @Transactional
    public void deleteCredential(Long userId) {
        credentialRepository.findByUserId(userId).ifPresent(credentialRepository::delete);
    }
}
