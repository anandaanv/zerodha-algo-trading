package com.dtech.kitecon.data;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Stores last traded price (LTP) for instruments.
 * Used for quick lookups during option resolution.
 */
@Entity
@Table(name = "instrument_ltp",
        indexes = {
                @Index(name = "idx_ltp_updated", columnList = "updated_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstrumentLtp {

    @Id
    @Column(name = "tradingsymbol", nullable = false, length = 64)
    private String tradingSymbol;

    @Column(name = "ltp")
    private Double ltp;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
