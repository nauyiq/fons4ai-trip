package com.fons.cloud.ai.trip.agent.pipeline;

import com.fons.cloud.ai.agent.api.AgentRun;
import com.fons.cloud.ai.trip.agent.pipeline.support.LlmQueryWritingStepHandler;
import com.fons.cloud.ai.trip.agent.pipeline.support.SemanticsRecognitionStepHandler;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.common.pipeline.Pipeline;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutePipeline {
    private final Pipeline<AgentPipelineExecuteContext> pipeline = new Pipeline<>(new Pipeline.Config(true, false));

    private final SemanticsRecognitionStepHandler semanticsRecognitionStepHandler;
    private final LlmQueryWritingStepHandler llmQueryWritingStepHandler;

    @PostConstruct
    public void init() {
        this.pipeline
                .addNext(semanticsRecognitionStepHandler)
                .addNext(llmQueryWritingStepHandler);
    }

    public record PipelineResult(
        String workId,
        String queryRewriteResult,
        IntentRecognitionResult recognitionResult,
        AgentRun masterAgentRun) {


    }

}
