package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.db.ScreenerRunEntity;
import com.dtech.algo.screener.db.ScreenerRunRepository;
import com.dtech.algo.screener.db.ScreenerUowEntity;
import com.dtech.algo.screener.db.ScreenerUowRepository;
import com.dtech.algo.screener.enums.WorkflowStep;
import com.dtech.algo.screener.enums.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScreenerRunLogService {

    private final ScreenerUowRepository uowRepository;
    private final ScreenerRunRepository runRepository;
    private final ObjectMapper objectMapper;

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

    public void markFinal(Long runId, Boolean finalPassed, Verdict finalVerdict) {
        if (runId == null) return;
        runRepository.findById(runId).ifPresent(run -> {
            run.setFinalPassed(finalPassed);
            run.setFinalVerdict(finalVerdict);
            runRepository.save(run);
        });
    }
}
