package com.epam.aidial.evaluation.service.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * An MCP-tool chain element. Deserializable so the chain model has a real seam for MCP chaining, but
 * <b>rejected at suite save with HTTP 400</b> — MCP chaining is not implemented. Its step executor
 * throws {@code UnsupportedOperationException} as an unreachable-by-construction backstop.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Schema(description = "An MCP tool invocation in the chain. Not supported yet — rejected at suite save.")
public class McpToolChainRequestDto extends ChainRequestDto {

    @Override
    public ChainRequestType getType() {
        return ChainRequestType.MCP_TOOL;
    }
}
