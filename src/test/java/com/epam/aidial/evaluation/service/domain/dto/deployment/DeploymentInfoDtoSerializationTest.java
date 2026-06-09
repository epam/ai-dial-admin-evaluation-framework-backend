package com.epam.aidial.evaluation.service.domain.dto.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DeploymentInfoDto JSON serialization")
class DeploymentInfoDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("DialModelInfoDto serializes with $type dial-model (kebab-case)")
    void dialModelInfoDtoSerializesWithKebabCaseType() throws JacksonException {
        DialModelInfoDto dto = DialModelInfoDto.builder()
                .deploymentId("gpt-5")
                .displayName("GPT-5")
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"$type\":\"dial-model\"");
        assertThat(json).doesNotContain("dial_model");
    }

    @Test
    @DisplayName("DialApplicationInfoDto serializes with $type dial-application (kebab-case)")
    void dialApplicationInfoDtoSerializesWithKebabCaseType() throws JacksonException {
        DialApplicationInfoDto dto = DialApplicationInfoDto.builder()
                .deploymentId("EntityExtractor")
                .displayName("Entity Extractor")
                .build();

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"$type\":\"dial-application\"");
        assertThat(json).doesNotContain("dial_application");
    }

    @Test
    @DisplayName("round-trip deserializes to correct subtype by $type")
    void roundTripDeserializesToCorrectSubtype() throws JacksonException {
        String modelJson = "{\"$type\":\"dial-model\",\"deploymentId\":\"m1\",\"displayName\":\"Model 1\"}";
        String appJson = "{\"$type\":\"dial-application\",\"deploymentId\":\"a1\",\"displayName\":\"App 1\"}";

        DeploymentInfoDto model = objectMapper.readValue(modelJson, DeploymentInfoDto.class);
        DeploymentInfoDto app = objectMapper.readValue(appJson, DeploymentInfoDto.class);

        assertThat(model).isInstanceOf(DialModelInfoDto.class);
        assertThat(model.getDeploymentId()).isEqualTo("m1");
        assertThat(app).isInstanceOf(DialApplicationInfoDto.class);
        assertThat(app.getDeploymentId()).isEqualTo("a1");
    }
}
