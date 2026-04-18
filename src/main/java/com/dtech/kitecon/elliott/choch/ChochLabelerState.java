package com.dtech.kitecon.elliott.choch;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChochLabelerState {
    private ChochEvent lastBullishChoch;
    private ChochEvent lastBearishChoch;
    private ChochImpulseCount pending;
    private List<ChochImpulseCount> completed = new ArrayList<>();
    private List<ChochEvent> orphanedChochs = new ArrayList<>();
}
