package com.dtech.aitrader.v2.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Top-level Agent 1 JSON response. Tolerant of unknown fields so prompt versions can evolve. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Agent1Output {
    private String symbol;
    private String scan_timestamp;
    private List<PlanGroupSpec> plan_groups;
    private List<FlagSpec> flags;
    private String scan_summary;
    private String no_plan_reason;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanGroupSpec {
        private String id;
        /** CREATE | UPDATE | SUPERSEDE | RETIRE */
        private String action;
        private String supersedes_group_id;
        private String underlying_hypothesis;
        private String structural_validation;
        private List<String> source_drawing_ids;
        private List<String> source_label_ids;
        private List<String> source_journal_note_ids;
        private List<String> playbook_rules_applied;
        private List<String> applied_user_comment_ids;
        private DecisionZoneSpec decision_zone;
        private String valid_until;
        private List<BranchSpec> branches;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DecisionZoneSpec {
        private Double low;
        private Double high;
        private String rationale;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BranchSpec {
        private String id;
        private String label;
        /** LONG | SHORT */
        private String direction;
        private List<Double> entry_zone;
        private String trigger_condition;
        private List<String> required_pattern;
        private Double stop_loss;
        private List<Double> targets;
        private Double invalidation_level;
        private String invalidation_rule;
        private List<String> sibling_kill_branches;
        private Double confidence;
        private String reasoning;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlagSpec {
        private String id;
        /** INFO | WARNING | BLOCKING */
        private String severity;
        private String category;
        private String title;
        private String details;
        private String affects_plan_group_id;
        /** array of strings OR string "whole_group" OR null — parsed as JsonNode to tolerate either. */
        private JsonNode affects_branch_ids;
        private List<String> source_drawing_ids;
        private List<String> source_label_ids;
        private List<String> related_playbook_rule_ids;
        private String suggested_resolution;
    }
}
