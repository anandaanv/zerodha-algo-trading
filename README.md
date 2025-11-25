# Comprehensive Open-AI enabled Charting and Screening system

The idea of the project is very simple. We want to provide a platform whree you can `code` screeners.
As of now, we are not looking at realtime algo trading, withough the repo name suggests it, but we are looking at a comprehensive screening mechanism which can go beyond simple spot price analysis.

## What we have done
1. We keep on syncing the market data from Zerodha Kite Connect API.
2. We have created a platform where you can write your own screeners in Kotlin.
3. We also have different notations, like 
    * CE1, CE2, CE3, CE_1, CE_2, CE_3 etc for in-the-money and out-of-the-money positions. 
    * Same way we have sometthing like FUT1, FUT2 etc for Futures.
4. You can assign them as an alias in the screener.
5. Using these alias, you can create your own strategy.
    The strategy may look like this:
    ```kotlin
    import com.dtech.algo.screener.ScreenerContext
    import com.dtech.algo.screener.SignalCallback
    import com.dtech.algo.screener.dsl.KDsl.dsl

        fun screener(ctx: ScreenerContext, cb: SignalCallback) = dsl(ctx, cb).run {
        val long = sma("wave", 20).crossesOver(sma(50))
        entryIf(long, "sma-crossover")
        exitIf(!long, "sma-crossdown")

        // Return ScreenerOutput via DSL
        output(
            long,
            mapOf(
                "signal" to if (long) "long" else "none"
            )
        )
    }
6. We also have a charting engine where we use tradingview lightweight charting library. This library lets you create charts and different studies on the charts.
7. is a stock passes the kotlin screener, we take these charts, along with the studies and indicators drawn on them, and send them to openai vision for analysis..
8. You can implement your own openai prompt for the chart analysis.
9. if any stock pass these both conditions, we are displaying it on the trades page, where you can trade it later.

# Whats there in the repo
1. Main project in the src folder for backend.
2. Few db scripts for initial db setup, but you will have to discover missing values and insert them.
3. A sample screener in the screener folder.
4. UI for creating screeners and charting is inside the ui folder.

Thats it for now.. You are welcome to contribute!

There is a lot more to come, and we can build it together.