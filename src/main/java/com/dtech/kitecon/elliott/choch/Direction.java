package com.dtech.kitecon.elliott.choch;

public enum Direction {
    BULLISH, BEARISH;

    public Direction opposite() {
        return this == BULLISH ? BEARISH : BULLISH;
    }
}
