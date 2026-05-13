package com.dtech.aitrader.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RunBatchResponse {
    private String batchId;
    private int queued;
}
