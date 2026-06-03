package com.epam.aidial.evaluation.service.domain.dto;

import com.epam.aidial.evaluation.constants.TestSuiteRunConstants;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteRunUpdateDto {

    @NotBlank
    @Size(max = TestSuiteRunConstants.MAX_TEST_RUN_NAME_LENGTH)
    private String testRunName;
}
