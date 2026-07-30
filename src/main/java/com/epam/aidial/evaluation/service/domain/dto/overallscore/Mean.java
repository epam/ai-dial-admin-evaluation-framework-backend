package com.epam.aidial.evaluation.service.domain.dto.overallscore;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code overall = mean(all metric output)} — no parameters. Resolved at Phase 3 against whatever
 * numeric metric fields the run currently has, not a list stored on the suite.
 */
@Schema(description = "Unweighted mean of all of the run's numeric metric fields, discovered at computation time.")
public record Mean() implements OverallScoreDefinition {}
