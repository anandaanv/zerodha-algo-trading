package com.dtech.kitecon.service;

import org.junit.jupiter.api.Test;

public class PnLCalculator {

  @Test
  void calculatePnL() {
    double amount = 50000.0;
    double tentativePrice = 240;
    double marginPercentage = 21.0;
    double buyProfit = 250.0;
    double sellProfit = 118.0;
    double brokerage = 138.0;

    int numberTrades = 298 + 275;

    System.out.println();

    double availableMargin = amount * 100 / marginPercentage;
    System.out.println("available margin     = " + Math.floor(availableMargin));

    double volume = availableMargin / tentativePrice;
    System.out.println("recommended volume   = " + Math.floor(volume));

    double totalBrokerage = numberTrades * brokerage;
    System.out.println("total brokerage      = " + Math.floor(totalBrokerage));

    double ledgerProfit = ( buyProfit + sellProfit) * volume;
    System.out.println("ledger profit        = " + Math.floor(ledgerProfit));

    double actualProfit = ledgerProfit - totalBrokerage;
    System.out.println("actual profit        = " + Math.floor(actualProfit));

    double profitPercentage = actualProfit * 100 / amount;
    System.out.println("profit percentage    = " + Math.floor(profitPercentage));

    System.out.println();

  }

}
