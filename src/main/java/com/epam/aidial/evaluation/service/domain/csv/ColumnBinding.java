package com.epam.aidial.evaluation.service.domain.csv;

/**
 * A CSV column header bound to its destination: {@code mappedTo} is {@link #MAPPED_TO_TEST_CASE_NAME},
 * {@link #MAPPED_TO_TURN_INDEX}, or {@link #MAPPED_TO_DATA}; {@code fieldName} is the destination
 * field/data-map key name (equal to {@code headerName} for data columns). Public because
 * {@link CsvSchemaFieldBuilder} (in this package) and {@code CsvImportService} (in
 * {@code service.domain}) both need it.
 */
public record ColumnBinding(String headerName, String mappedTo, String fieldName) {

    /** The three legal {@code mappedTo} values — no other value is ever produced or consumed. */
    public static final String MAPPED_TO_TEST_CASE_NAME = "testCaseName";

    public static final String MAPPED_TO_TURN_INDEX = "turnIndex";
    public static final String MAPPED_TO_DATA = "data";
}
