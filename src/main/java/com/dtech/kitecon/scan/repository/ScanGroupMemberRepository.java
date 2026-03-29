package com.dtech.kitecon.scan.repository;

import com.dtech.kitecon.scan.entity.ScanGroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScanGroupMemberRepository extends JpaRepository<ScanGroupMember, Long> {
    List<ScanGroupMember> findByUserId(Long userId);
    List<ScanGroupMember> findByGroupId(Long groupId);
    boolean existsByGroupIdAndUserId(Long groupId, Long userId);
}
