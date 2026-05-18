package com.dtech.aitrader.v2.memsys.auth;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.kitecon.kite.entity.UserKiteConfig;
import com.dtech.kitecon.kite.repository.UserKiteConfigRepository;
import com.dtech.kitecon.service.copilot.CopilotEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges algotrade's existing per-user Kite credentials (in {@code user_kite_config})
 * into memsys's per-tenant Kite skill vault via the {@code memsys_enable_kite} tool.
 *
 * Uses Mode C (password + TOTP secret) so the user's tenant on memsys can auto-renew
 * the Zerodha access token daily without manual paste. See memsys memory
 * {@code 95159c69} for the Mode C design.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemsysKiteOnboardingService {

    private final UserKiteConfigRepository kiteRepo;
    private final CopilotEncryptionService encryptionService;
    private final MemsysClient memsys;

    /**
     * Enable the Kite skill on memsys for the given algotrade user. Reads creds from
     * {@code user_kite_config}, decrypts password + TOTP secret, and calls
     * {@code memsys_enable_kite}. Idempotent on memsys side — re-calling overwrites.
     *
     * @return memsys's raw response (tool output JSON) — useful for the controller to surface status
     * @throws IllegalStateException if no active Kite config exists for the user or required fields are missing
     */
    public JsonNode enableForUser(Long platformUserId) {
        UserKiteConfig cfg = kiteRepo.findFirstByPlatformUserIdAndActiveTrue(platformUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "no active user_kite_config row for platform_user_id=" + platformUserId
                                + " — set up Kite locally first"));

        require(cfg.getApiKey(), "apiKey");
        require(cfg.getApiSecret(), "apiSecret");
        require(cfg.getKiteUserId(), "kiteUserId");
        require(cfg.getZerodhaPasswordEncrypted(), "zerodhaPasswordEncrypted");
        require(cfg.getTotpSecretEncrypted(), "totpSecretEncrypted");

        String password = encryptionService.decrypt(cfg.getZerodhaPasswordEncrypted());
        String totp = encryptionService.decrypt(cfg.getTotpSecretEncrypted());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("api_key", cfg.getApiKey());
        args.put("api_secret", cfg.getApiSecret());
        args.put("user_id", cfg.getKiteUserId());
        args.put("password", password);
        args.put("totp_secret", totp);

        log.info("[memsys-kite-onboard] calling memsys_enable_kite for platformUserId={} kite_user_id={}",
                platformUserId, cfg.getKiteUserId());
        JsonNode result = memsys.rawToolCall(platformUserId, "memsys_enable_kite", args);
        log.info("[memsys-kite-onboard] memsys_enable_kite returned for platformUserId={}: keys={}",
                platformUserId, result == null ? "null" : fieldNames(result));
        return result;
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("user_kite_config." + name + " is empty — cannot enable Kite on memsys");
        }
    }

    private static String fieldNames(JsonNode n) {
        if (!n.isObject()) return n.getNodeType().toString();
        List<String> names = new java.util.ArrayList<>();
        n.fieldNames().forEachRemaining(names::add);
        return String.join(",", names);
    }
}
