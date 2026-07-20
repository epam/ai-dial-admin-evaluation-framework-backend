package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Per-row write-time validation of the row-based multi-turn grouping fields ({@code conversationId} /
 * {@code turnIndex}). Enforces the cheap, single-row invariants (both-or-neither presence and turn-index
 * bounds) as hard 400s; conversation-level completeness/contiguity is a run-time (snapshot) concern and is
 * intentionally not checked here (client-managed integrity). Shared by the CRUD and CSV-import write paths.
 */
@Component
@LogExecution
public class ConversationFieldsValidator {

    public void validate(UUID conversationId, Integer turnIndex) {
        boolean hasConversation = conversationId != null;
        boolean hasTurn = turnIndex != null;
        if (hasConversation != hasTurn) {
            throw new ValidationException("conversationId and turnIndex must be provided together");
        }
        // No upper bound on turnIndex at write time: surviving turns may be non-contiguous (turns disabled or
        // filtered out at the start/middle/end), so a high authored index no longer implies a large turn count.
        // The turn-count limit is enforced at snapshot against the surviving-turn count (see ConversationAssembler).
        if (hasTurn && turnIndex < 0) {
            throw new ValidationException("turnIndex must be >= 0");
        }
    }
}
