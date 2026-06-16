package com.epam.aidial.evaluation.data.db.model.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PageRequestTest {

    @Test
    void shouldThrowWhenSizeIsZero() {
        PageRequest pageRequest = PageRequest.of(0, 0);

        assertThatThrownBy(pageRequest::getValidatedSize).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowWhenPageIsNegative() {
        PageRequest pageRequest = PageRequest.of(-10, 20);

        assertThatThrownBy(pageRequest::getOffset).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldComputeOffsetForValidInputs() {
        PageRequest pageRequest = PageRequest.of(2, 1);

        assertThat(pageRequest.getOffset()).isEqualTo(2);
    }
}
