package com.epam.aidial.evaluation.service.domain.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SseStatusEventDto {

    private UUID runId;
    private UUID testSuiteId;
    private String status;
    private String message;
    private Long timestamp;
}
