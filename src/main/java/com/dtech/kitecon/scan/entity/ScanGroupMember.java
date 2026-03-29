package com.dtech.kitecon.scan.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "scan_group_members")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long groupId;

    @Column(nullable = false)
    private Long userId;
}
