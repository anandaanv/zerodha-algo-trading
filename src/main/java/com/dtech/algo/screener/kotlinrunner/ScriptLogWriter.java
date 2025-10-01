package com.dtech.algo.screener.kotlinrunner;

import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.runtime.ScreenerRunLogService;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.util.Map;

/**
 * A Writer that captures script output and forwards it to DB via ScreenerRunLogService.
 * It also optionally delegates to an underlying writer to preserve console output.
 */
public final class ScriptLogWriter extends Writer {

    private final ScreenerRunLogService runLogService;
    private final Long runId;
    private final WorkflowStep step;
    private final String channel; // "stdout" or "stderr"
    private final Writer delegate;
    private final StringBuilder buffer = new StringBuilder();

    public ScriptLogWriter(ScreenerRunLogService runLogService,
                           Long runId,
                           WorkflowStep step,
                           String channel,
                           Writer delegate) {
        this.runLogService = runLogService;
        this.runId = runId;
        this.step = step;
        this.channel = channel == null ? "stdout" : channel;
        this.delegate = delegate;
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        if (delegate != null) {
            delegate.write(cbuf, off, len);
        }
        if (len <= 0) return;
        buffer.append(cbuf, off, len);
        flushLines(false);
    }

    @Override
    public void flush() throws IOException {
        if (delegate != null) delegate.flush();
        flushLines(true);
    }

    @Override
    public void close() throws IOException {
        flush();
        if (delegate != null) delegate.close();
    }

    private void flushLines(boolean flushRemainder) {
        int idx;
        while ((idx = indexOfNewline(buffer)) >= 0) {
            String line = buffer.substring(0, idx);
            buffer.delete(0, idx + 1);
            logLine(line);
        }
        if (flushRemainder && buffer.length() > 0) {
            String line = buffer.toString();
            buffer.setLength(0);
            logLine(line);
        }
    }

    private static int indexOfNewline(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '\n') return i;
            if (c == '\r') return i;
        }
        return -1;
    }

    private void logLine(String line) {
        if (runLogService == null || runId == null || runId == 0) return;
        try {
            runLogService.logStep(
                    runId,
                    step,
                    Map.of(
                            "channel", channel,
                            "ts", Instant.now().toString()
                    ),
                    Map.of(
                            "msg", line
                    ),
                    true,
                    null
            );
        } catch (Throwable ignore) {
            // never break the run on logging failure
        }
    }
}
