package com.epam.aidial.evaluation.service.domain.dto.overallscore;

/**
 * {@code overall = mean(all metric output)} — no parameters. Resolved at Phase 3 against whatever
 * numeric metric fields the run currently has, not a list stored on the suite.
 */
public record Mean() implements OverallScoreDefinition {}
