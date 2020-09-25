package com.dtech.kitecon.service;

import org.junit.jupiter.api.Test;

public class PnLCalculator {

  @Test
  void calculatePnL() {
    Double amount = 50000.0;
    Double tentativePrice = 240.0;
    Double marginPercentage = 21.0;
    Double buyProfit = 250.0;
    Double sellProfit = 118.0;
    Double brokerage = 138.0;

    Integer numberTrades = 298 + 275;

    System.out.println();

    Double availableMargin = amount * 100 / marginPercentage;
    System.out.println("available margin     = " + Math.floor(availableMargin));

    Double volume = availableMargin / tentativePrice;
    System.out.println("recommended volume   = " + Math.floor(volume));

    Double totalBrokerage = numberTrades * brokerage;
    System.out.println("total brokerage      = " + Math.floor(totalBrokerage));

    Double ledgerProfit = ( buyProfit + sellProfit) * volume;
    System.out.println("ledger profit        = " + Math.floor(ledgerProfit));

    Double actualProfit = ledgerProfit - totalBrokerage;
    System.out.println("actual profit        = " + Math.floor(actualProfit));

    Double profitPercentage = actualProfit * 100 / amount;
    System.out.println("profit percentage    = " + Math.floor(profitPercentage));

    System.out.println();

  }

}
