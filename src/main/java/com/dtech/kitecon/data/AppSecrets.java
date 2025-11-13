package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Data
@NoArgsConstructor
@Table(name = "app_secrets", uniqueConstraints = @UniqueConstraint(columnNames = {"env", "prop_key"}))
public class AppSecrets {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 32)
  private String env;

  @Column(name = "prop_key", nullable = false, length = 191)
  private String propKey;

  @Column(name = "prop_value", nullable = false, columnDefinition = "TEXT")
  private String propValue;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  @PreUpdate
  protected void onUpdate() {
    this.updatedAt = Instant.now();
  }
}
