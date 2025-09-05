package com.dtech.kitecon.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(name = "kite_connect_settings")
@Data
public class KiteConnectSettings {

  @Id
  private Long id; // singleton row, e.g., 1L

  @Column(name = "api_key")
  private String apiKey;

  @Column(name = "user_id")
  private String userId;

  @Column(name = "secret")
  private String secret;

  @Column(name = "access_token")
  private String accessToken;

  @Column(name = "public_token")
  private String publicToken;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  @PreUpdate
  public void touch() {
    this.updatedAt = LocalDateTime.now();
  }
}
