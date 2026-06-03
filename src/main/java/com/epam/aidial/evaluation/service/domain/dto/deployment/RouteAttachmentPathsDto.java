package com.epam.aidial.evaluation.service.domain.dto.deployment;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Attachment paths for request/response")
public class RouteAttachmentPathsDto {

    @Schema(description = "JSON paths for request body attachments")
    private List<String> requestBody;

    @Schema(description = "JSON paths for response body attachments")
    private List<String> responseBody;
}
