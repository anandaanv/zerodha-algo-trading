package com.dtech.kitecon.config;

import com.dtech.kitecon.repository.KiteConnectSettingsRepository;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import java.io.IOException;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

@Component
@Getter
@RequiredArgsConstructor
public class KiteConnectConfig {

  @Value("${kite.api.key}")
  private String apiKey;

  @Value("${kite.api.user}")
  private String userId;

  @Value("${kite.api.secret}")
  private String secret;

  private KiteConnect kiteConnect;

  private final KiteConnectSettingsRepository settingsRepository;

  public final void initialize(String requestToken) throws KiteException, IOException {
    this.kiteConnect = new KiteConnect(apiKey);

    //If you wish to enable debug logs send true in the constructor, this will log request and response.
    //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", true);

    // If you wish to set proxy then pass proxy as a second parameter in the constructor with api_key. syntax:- new KiteConnect("xxxxxxyyyyyzzz", proxy).
    //KiteConnect kiteConnect = new KiteConnect("xxxxyyyyzzzz", userProxy, false);

    // Set userId
    kiteConnect.setUserId(userId);

    // Get login url
    String url = kiteConnect.getLoginURL();

    User user = kiteConnect.generateSession(requestToken, secret);
    kiteConnect.setAccessToken(user.accessToken);
    kiteConnect.setPublicToken(user.publicToken);

    // Persist updated tokens
    upsertTokens(user.accessToken, user.publicToken);
  }

  public void initFromDatabase() {
    Optional<com.dtech.kitecon.persistence.KiteConnectSettings> existing = settingsRepository.findById(1L);
    com.dtech.kitecon.persistence.KiteConnectSettings settings;
    if (existing.isPresent()) {
      settings = existing.get();
      if (settings.getApiKey() != null) this.apiKey = settings.getApiKey();
      if (settings.getUserId() != null) this.userId = settings.getUserId();
      if (settings.getSecret() != null) this.secret = settings.getSecret();
    } else {
      settings = new com.dtech.kitecon.persistence.KiteConnectSettings();
      settings.setId(1L);
      settings.setApiKey(this.apiKey);
      settings.setUserId(this.userId);
      settings.setSecret(this.secret);
      settingsRepository.save(settings);
    }

    this.kiteConnect = new KiteConnect(apiKey);
    kiteConnect.setUserId(userId);

    if (settings.getAccessToken() != null) {
      kiteConnect.setAccessToken(settings.getAccessToken());
    }
    if (settings.getPublicToken() != null) {
      kiteConnect.setPublicToken(settings.getPublicToken());
    }
  }

  private void upsertTokens(String accessToken, String publicToken) {
    com.dtech.kitecon.persistence.KiteConnectSettings settings =
        settingsRepository.findById(1L).orElseGet(() -> {
          com.dtech.kitecon.persistence.KiteConnectSettings s = new com.dtech.kitecon.persistence.KiteConnectSettings();
          s.setId(1L);
          s.setApiKey(this.apiKey);
          s.setUserId(this.userId);
          s.setSecret(this.secret);
          return s;
        });
    settings.setAccessToken(accessToken);
    settings.setPublicToken(publicToken);
    settingsRepository.save(settings);
  }

  @Bean
  public KiteConnect getKiteConnect() {
    return kiteConnect;
  }

}
