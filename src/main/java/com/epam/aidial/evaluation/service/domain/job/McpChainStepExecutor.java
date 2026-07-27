package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import org.springframework.stereotype.Component;

/**
 * Stub executor for {@code MCP_TOOL} chain elements — <b>unreachable by construction</b>. MCP chaining is
 * not implemented; an {@code MCP_TOOL}-typed chain element is rejected at suite save with HTTP 400 by
 * {@code ChainConfigurationValidator}.
 *
 * <p>This exists alongside that save-time rejection rather than instead of it. Save-time alone would leave an
 * SPI method no implementation honors; the stub alone would let an author save a suite guaranteed to fail
 * later, surfacing as a 500 mid-run instead of a 400 at the moment of the mistake. Together, the 400 is the
 * user-facing contract and this is the defensive backstop.
 */
@Component
@LogExecution
public class McpChainStepExecutor implements ChainStepExecutor {

    @Override
    public ChainRequestType supportedType() {
        return ChainRequestType.MCP_TOOL;
    }

    @Override
    public ChainStepOutcome execute(ChainStepRequest step) {
        throw new UnsupportedOperationException("MCP chaining is not supported: chain request "
                + step.request().index() + " ('" + step.request().label()
                + "') declares type MCP_TOOL, which suite save rejects with HTTP 400. Reaching this point "
                + "means the save-time guard was bypassed.");
    }
}
