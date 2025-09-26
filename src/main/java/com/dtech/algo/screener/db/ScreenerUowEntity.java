package com.dtech.algo.screener.db;

import com.dtech.algo.screener.enums.WorkflowStep;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "screener_uow",
        indexes = {
                @Index(name = "idx_screener_uow_run_id", columnList = "screener_run_id"),
                @Index(name = "idx_screener_uow_step_type", columnList = "step_type")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScreenerUowEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK to screener_run
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screener_run_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_screener_uow_run"))
    private ScreenerRunEntity screenerRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 64)
    private WorkflowStep stepType;

    // Raw JSON payloads for now
    @Lob
    @Column(name = "input_json")
    private String inputJson;

    @Lob
    @Column(name = "output_json")
    private String outputJson;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
