package com.dtech.wavelab.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "wl_watchlist_item", indexes = {
    @Index(name = "idx_watchlist_symbol", columnList = "watchlistId, symbol")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WlWatchlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long watchlistId;

    @Column(nullable = false, length = 64)
    private String symbol;

    @Column(nullable = true, length = 16)
    private String exchange;

    @Column(nullable = true)
    private Integer displayOrder;

    @PrePersist
    public void prePersist() {
        if (this.exchange == null) {
            this.exchange = "NSE";
        }
    }
}
