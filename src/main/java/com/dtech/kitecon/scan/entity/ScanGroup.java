package com.dtech.kitecon.scan.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scan_groups")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "max_scans_per_hour")
    private Integer maxScansPerHour;

    @Column(name = "screener_access", nullable = false)
    @Builder.Default
    private boolean screenerAccess = false;
}
