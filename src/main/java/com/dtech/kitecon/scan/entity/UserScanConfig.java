package com.dtech.kitecon.scan.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_scan_configs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserScanConfig {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "max_scans_per_hour")
    private Integer maxScansPerHour;
}
