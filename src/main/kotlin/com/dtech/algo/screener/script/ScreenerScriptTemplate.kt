package com.dtech.algo.screener.script

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    fileExtension = "kscr.kts",
    displayName = "Screener Script",
    compilationConfiguration = ScreenerScriptConfig::class
)
abstract class ScreenerScript

object ScreenerScriptConfig : ScriptCompilationConfiguration({
    // Make DSL available without explicit imports in scripts
    defaultImports(
        "com.dtech.algo.screener.ScreenerContext",
        "com.dtech.algo.screener.SignalCallback",
        "com.dtech.algo.screener.dsl.KDsl.dsl",
        "com.dtech.algo.screener.dsl.KDsl.tags",
        "com.dtech.algo.screener.dsl.*",
        "com.dtech.algo.screener.dsl.averages.*",
        "com.dtech.algo.screener.dsl.oscillators.*",
        "com.dtech.algo.screener.dsl.trend.*",
        "com.dtech.algo.screener.dsl.bands.*"
    )

    // Give IDE and runtime the full app classpath so completion resolves project classes
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})
