import com.dtech.algo.screener.ScreenerContext
import com.dtech.algo.screener.SignalCallback
import com.dtech.algo.screener.dsl.KDsl.dsl

// Port of "imp-technicals" in compact DSL form.
// Assumes aliases: "wave" (current), "tide" (higher TF) are provided.

fun screener(ctx: ScreenerContext, cb: SignalCallback) = dsl(ctx, cb).run {
    // Defaults
    defaultAlias("wave")

    // Wave (current TF)
    val ema5 = ema(5)
    val ema13 = ema(13)
    val ema26 = ema(26)
    val rsi14 = rsi(14)
    val stochK = stochK(14, 3)
    val b = bbands(20, 2.0)
    val adx14 = adx(14)
    val dPlus = diPlus(14)
    val dMinus = diMinus(14)

    // Tide (higher TF)
    val tideMacd = macd("tide", 9, 26, 9)

    // Gating logic similar to the Pine version
    val emaAlignedUp = ema5.gt(ema13) && ema13.gt(ema26)
    val emaCrossAnyUp = emaAlignedUp
            || ema5.crossesOver(ema13) || ema5.crossesOver(ema26) || ema13.crossesOver(ema26)

    val emaAlignedDown = ema5.lt(ema13) && ema13.lt(ema26)
    val emaCrossAnyDown = emaAlignedDown
            || ema5.crossesUnder(ema13) || ema5.crossesUnder(ema26) || ema13.crossesUnder(ema26)

    val rsiUptick = rsi14.slopeUp()
    val rsiDowntick = rsi14.slopeDown()
    val stochUptick = stochK.slopeUp()
    val stochDowntick = stochK.slopeDown()

    val macdUp = tideMacd.macd.slopeUp()
    val macdDown = tideMacd.macd.slopeDown()

    val bbcup = b.upper.slopeUp()
    val bbcdn = b.lower.slopeDown()

    val doubleBuy = macdUp && (rsiUptick || stochUptick) && emaCrossAnyUp
    val doubleSell = macdDown && (rsiDowntick || stochDowntick) && emaCrossAnyDown

    val papaBuy = rsi14.gt(60.0) && bbcup
    val papaSell = rsi14.lt(40.0) && bbcdn

    val adxUptick = adx14.slopeUp() && adx14.lt(60.0)
    val adxDowntick = adx14.slopeDown()

    val adxGreen = (dPlus.now() - dMinus.now() > 3.0) && adxUptick
    val adxRed = (dMinus.now() - dPlus.now() > 3.0) && adxDowntick

    val finalBuy = doubleBuy && papaBuy && adxGreen
    val finalSell = doubleSell && papaSell && adxRed

    entryIf(finalBuy, "imp-tech-buy")
    exitIf(finalSell, "imp-tech-sell")
}
