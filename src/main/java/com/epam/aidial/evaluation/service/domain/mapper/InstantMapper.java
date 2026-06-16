package com.epam.aidial.evaluation.service.domain.mapper;

import java.time.Instant;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public class InstantMapper {

    /**
     * Converts an Instant to a Long representing epoch milliseconds.
     *
     * @param instant the Instant to convert
     * @return the epoch milliseconds as a Long, or null if the input is null
     */
    public Long mapInstantToLong(Instant instant) {
        return instant != null ? instant.toEpochMilli() : null;
    }

    /**
     * Converts a Long representing epoch milliseconds to an Instant.
     *
     * @param epochMilli the epoch milliseconds to convert
     * @return the Instant, or null if the input is null
     */
    public Instant mapLongToInstant(Long epochMilli) {
        return epochMilli != null ? Instant.ofEpochMilli(epochMilli) : null;
    }
}
