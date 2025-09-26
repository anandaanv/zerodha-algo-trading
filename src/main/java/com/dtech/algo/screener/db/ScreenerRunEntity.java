package com.dtech.algo.screener.db;

import com.dtech.algo.screener.enums.SchedulingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "screener_run",
        indexes = {
                @Index(name = "idx_screener_run_screener_id", columnList = "screener_id"),
                @Index(name = "idx_screener_run_symbol", columnList = "symbol"),
                @Index(name = "idx_screener_run_status", columnList = "scheduling_status"),
                @Index(name = "idx_screener_run_status_due", columnList = "scheduling_status, execute_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screener_id", nullable = false)
    private Long screenerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scheduling_status", nullable = false, length = 32)
    private SchedulingStatus schedulingStatus;

    @Column(name = "symbol", nullable = false, length = 128)
    private String symbol;

    @Column(name = "timeframe", length = 64)
    private String timeframe;

    @Column(name = "execute_at", nullable = false)
    private Instant executeAt;

    // e.g., "openai", "script", etc.
    @Column(name = "current_state", length = 64)
    private String currentState;

    // Overall result of the run
    @Column(name = "final_passed")
    private Boolean finalPassed;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_verdict", length = 16)
    private com.dtech.algo.screener.enums.Verdict finalVerdict;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "screenerRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScreenerUowEntity> uowList = new ArrayList<>();
}
