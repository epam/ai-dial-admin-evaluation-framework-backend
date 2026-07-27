package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * An HTTP chain element — the only implemented chain-request kind, and the default when {@code type}
 * is absent from the payload.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "An HTTP request in the chain, issued against the suite-level deployment.")
public class HttpChainRequestDto extends ChainRequestDto {

    @Override
    public ChainRequestType getType() {
        return ChainRequestType.HTTP;
    }
}
