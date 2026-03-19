package com.dtech.dhan.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Entity for storing Dhan authentication settings
 * Singleton pattern - single row with id=1L
 */
@Entity
@Table(name = "dhan_connect_settings")
@Data
public class DhanConnectSettings {

    @Id
    private Long id; // singleton row, always 1L

    @Column(name = "client_id", length = 100)
    private String clientId;

    @Column(name = "access_token", length = 500)
    private String accessToken;

    @Column(name = "user_id", length = 100)
    private String userId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
