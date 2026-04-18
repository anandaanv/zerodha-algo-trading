package com.dtech.kitecon.elliott.choch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChochImpulseCount {

    public enum Status {
        PENDING_W2, PENDING_W3, W3_CONFIRMED, PENDING_W4, PENDING_W5, COMPLETED, INVALIDATED
    }

    private UUID chainId;
    private ChochEvent origin;
    private int originOriginalChochIdx;  // Original ChoCH pivot index (for state machine logic)
    private Direction direction;

    private int w1EndPivotIdx;
    private double w1EndPrice;

    private int w2EndPivotIdx;
    private ChochEvent w2End;

    private int w3EndPivotIdx;
    private double w3EndPrice;

    private int w4EndPivotIdx;
    private ChochEvent w4End;

    private int w5EndPivotIdx;
    private double w5EndPrice;

    private Status status;
    private ChochLabelConfidence w2Confidence;

    private double w2RetracePct;
    private double w3Ratio;
    private double w4RetracePct;
    private double w5Ratio;

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
