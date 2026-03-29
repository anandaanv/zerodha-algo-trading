package com.dtech.kitecon.scan.service;

import com.dtech.kitecon.scan.entity.*;
import com.dtech.kitecon.scan.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ScanGroupService {

    private final ScanGroupRepository groupRepository;
    private final ScanGroupMemberRepository memberRepository;

    public ScanGroup createGroup(String name, Integer maxScansPerHour) {
        return groupRepository.save(ScanGroup.builder()
                .name(name)
                .maxScansPerHour(maxScansPerHour)
                .build());
    }

    public List<ScanGroup> listGroups() {
        return groupRepository.findAll();
    }

    public void addMember(Long groupId, Long userId) {
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            memberRepository.save(ScanGroupMember.builder()
                    .groupId(groupId)
                    .userId(userId)
                    .build());
        }
    }

    public void removeMember(Long groupId, Long userId) {
        memberRepository.findByGroupId(groupId).stream()
                .filter(m -> m.getUserId().equals(userId))
                .forEach(memberRepository::delete);
    }

    public List<ScanGroupMember> getMembers(Long groupId) {
        return memberRepository.findByGroupId(groupId);
    }

    public boolean userHasScreenerAccess(Long userId) {
        return memberRepository.findByUserId(userId).stream()
                .map(m -> groupRepository.findById(m.getGroupId()))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .anyMatch(ScanGroup::isScreenerAccess);
    }

    public ScanGroup updateGroup(Long groupId, Boolean screenerAccess, Integer maxScansPerHour) {
        ScanGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
        if (screenerAccess != null) group.setScreenerAccess(screenerAccess);
        if (maxScansPerHour != null) group.setMaxScansPerHour(maxScansPerHour);
        return groupRepository.save(group);
    }
}
