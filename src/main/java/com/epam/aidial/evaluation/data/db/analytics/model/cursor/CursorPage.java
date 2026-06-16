package com.epam.aidial.evaluation.data.db.analytics.model.cursor;

import java.util.List;

public record CursorPage<T>(List<T> content, Cursor nextCursor, boolean hasMore) {}
