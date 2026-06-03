package com.epam.aidial.evaluation.service.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ResolvedMultipartBodyDto extends ResolvedBodyDto {

    private List<ResolvedFormPartDto> parts;

    @Override
    public String getContentType() {
        return "multipart/form-data";
    }
}
