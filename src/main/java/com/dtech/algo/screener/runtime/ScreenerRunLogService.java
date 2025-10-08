package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.db.ScreenerRunEntity;
import com.dtech.algo.screener.db.ScreenerRunRepository;
import com.dtech.algo.screener.db.ScreenerUowEntity;
import com.dtech.algo.screener.db.ScreenerUowRepository;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.enums.Verdict;
import com.dtech.algo.screener.trade.IdentifiedTradeService;
import com.dtech.algo.series.Interval;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ScreenerRunLogService {

    private final ScreenerUowRepository uowRepository;
    private final ScreenerRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final IdentifiedTradeService identifiedTradeService;

    public void logStep(Long runId, WorkflowStep step, Object input, Object output, Boolean success, String errorMessage) {
        if (runId == null || runId == 0) return;
        try {
            String inputJson = input == null ? null : objectMapper.writeValueAsString(input);
            String outputJson = output == null ? null : objectMapper.writeValueAsString(output);
            ScreenerUowEntity entity = ScreenerUowEntity.builder()
                    .screenerRun(ScreenerRunEntity.builder().id(runId).build())
                    .stepType(step)
                    .inputJson(inputJson)
                    .outputJson(outputJson)
                    .success(success)
                    .errorMessage(errorMessage)
                    .build();
            uowRepository.save(entity);
        } catch (Exception ignore) {
            // swallow to not break run on logging failure
        }
    }

    public void markFinal(Long runId, WorkflowStep step, Boolean finalPassed, Verdict finalVerdict) {
        if (runId == null) return;
        runRepository.findById(runId).ifPresent(run -> {
            run.setFinalPassed(finalPassed);
            run.setFinalVerdict(finalVerdict);
            runRepository.save(run);

            if (Boolean.TRUE.equals(finalPassed) && finalVerdict != null && finalVerdict != Verdict.WAIT) {
                String entry = null, target = null, stoploss = null;
                if (step != null) {
                    Optional<ScreenerUowEntity> uow = uowRepository.findTopByScreenerRunIdAndStepTypeOrderByCreatedAtDesc(runId, step);
                    if(uow.isPresent()) {
                        entry = uow.get().getInputJson();
                        target = uow.get().getOutputJson();
                        stoploss = uow.get().getOutputJson();
                    }
                }
                com.dtech.algo.screener.dto.OpenAiTradeOutput ai = com.dtech.algo.screener.dto.OpenAiTradeOutput.builder()
                        .entry(entry)
                        .target(target)
                        .stoploss(stoploss)
                        .build();

                identifiedTradeService.upsertOnMarkFinal(
                        run.getSymbol(),
                        Interval.valueOf(run.getTimeframe()),
                        finalVerdict,
                        true,
                        runId,
                        ai
                );
            }
        });
    }

    // Backward-compatible overload without step
    public void markFinal(Long runId, Boolean finalPassed, Verdict finalVerdict) {
        markFinal(runId, null, finalPassed, finalVerdict);
    }

    private static String findFirstValue(com.fasterxml.jackson.databind.JsonNode node, String key) {
        if (node == null) return null;
        if (node.isObject()) {
            var it = node.fieldNames();
            while (it.hasNext()) {
                String fn = it.next();
                var child = node.get(fn);
                if (fn.equalsIgnoreCase(key)) {
                    if (child != null && !child.isNull()) {
                        return child.isTextual() ? child.asText() : child.toString();
                    }
                }
                String v = findFirstValue(child, key);
                if (v != null) return v;
            }
        } else if (node.isArray()) {
            for (var it = node.elements(); it.hasNext(); ) {
                String v = findFirstValue(it.next(), key);
                if (v != null) return v;
            }
        }
        return null;
    }
}
