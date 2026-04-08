package com.dtech.kitecon.watchlist;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Entity
@Table(name = "watchlist_symbols")
@Data
@NoArgsConstructor
public class WatchlistSymbol {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "watchlist_id", nullable = false)
    private Watchlist watchlist;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "added_at")
    private Instant addedAt = Instant.now();
}
