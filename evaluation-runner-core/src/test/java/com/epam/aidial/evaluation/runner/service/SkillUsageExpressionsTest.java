package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the response-column expressions documented for detecting skill usage against the response shape of a
 * skill-aware DIAL application (the skill load is reported as a {@code Reading Skill: <name>} stage).
 */
@DisplayName("Skill usage response-column expressions")
class SkillUsageExpressionsTest {

    private static final String SKILL_USED =
            "$exists(choices[0].message.custom_content.stages[$contains(name, \"Reading Skill\")])";
    private static final String SKILL_STAGE =
            "choices[0].message.custom_content.stages[$contains(name, \"Reading Skill\")][0].name";

    private static final String WITH_SKILL = """
            {"choices":[{"message":{"role":"assistant","content":"A blue bird",
              "custom_content":{"stages":[
                {"name":"Reading Skill: vc-test [0.00s]","status":"completed"},
                {"name":"Usage Statistics","status":"completed"}]}}}]}
            """;
    private static final String WITHOUT_SKILL = """
            {"choices":[{"message":{"role":"assistant","content":"A blue bird on a black background.",
              "custom_content":{"state":{"responses_output":[]}}}}]}
            """;

    private DashjoinJsonataEvaluationService service;

    @BeforeEach
    void setUp() {
        final JsonataProperties properties = new JsonataProperties();
        properties.setEvaluationTimeoutMs(5000L);
        properties.setMaxRecursionDepth(500);
        service = new DashjoinJsonataEvaluationService(new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("skill_used is true when a 'Reading Skill' stage is present")
    void skillUsedTrueWhenStagePresent() {
        assertThat(service.evaluate(SKILL_USED, WITH_SKILL)).isEqualTo(true);
    }

    @Test
    @DisplayName("skill_used is false when the response has no stages")
    void skillUsedFalseWhenNoStage() {
        assertThat(service.evaluate(SKILL_USED, WITHOUT_SKILL)).isEqualTo(false);
    }

    @Test
    @DisplayName("skill_stage returns the 'Reading Skill' stage name, and null when absent")
    void skillStageReturnsName() {
        assertThat(service.evaluate(SKILL_STAGE, WITH_SKILL)).isEqualTo("Reading Skill: vc-test [0.00s]");
        assertThat(service.evaluate(SKILL_STAGE, WITHOUT_SKILL)).isNull();
    }
}
