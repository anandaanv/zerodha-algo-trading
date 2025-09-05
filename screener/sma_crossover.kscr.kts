import com.dtech.algo.screener.ScreenerContext
import com.dtech.algo.screener.SignalCallback
import com.dtech.algo.screener.dsl.KDsl.dsl

fun screener(ctx: ScreenerContext, cb: SignalCallback) = dsl(ctx, cb).run {
    val long = sma("wave", 20).crossesOver(sma(50))
    entryIf(long, "sma-crossover")
    exitIf(!long, "sma-crossdown")
}
