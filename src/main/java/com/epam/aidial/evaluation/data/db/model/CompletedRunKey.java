package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;

/** Light projection of a COMPLETED run: just what keyset paging and pass-count recording need. */
public record CompletedRunKey(UUID id, UUID suiteId, long createdAt) {}
