package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Cron trigger fields (APScheduler syntax). At least one field must be set; unset fields default to"
                        + " every value. Values are interpreted in the schedule's timezone.")
public class ScheduleCronDto {

    @Size(max = 64)
    @Schema(example = "2026")
    private String year;

    @Size(max = 64)
    @Schema(example = "*")
    private String month;

    @Size(max = 64)
    @Schema(example = "*")
    private String day;

    @Size(max = 64)
    @Schema(example = "*")
    private String week;

    @Size(max = 64)
    @Schema(example = "mon-fri")
    private String dayOfWeek;

    @Size(max = 64)
    @Schema(example = "2")
    private String hour;

    @Size(max = 64)
    @Schema(example = "*/10")
    private String minute;

    @Size(max = 64)
    @Schema(example = "0")
    private String second;
}
