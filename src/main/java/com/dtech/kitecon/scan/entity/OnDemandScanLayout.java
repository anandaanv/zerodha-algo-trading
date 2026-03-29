package com.dtech.kitecon.scan.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "on_demand_scan_layouts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OnDemandScanLayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scanId;

    @Column(nullable = false, length = 20)
    private String timeframe;

    @Column(nullable = false)
    private Integer tabOrder;

    @Column(name = "overlays_json", columnDefinition = "TEXT")
    private String overlaysJson;
}
