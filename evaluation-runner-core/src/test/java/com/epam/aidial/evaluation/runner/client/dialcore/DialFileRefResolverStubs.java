package com.epam.aidial.evaluation.runner.client.dialcore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;

/**
 * Test support for suites that mock {@link DialFileRefResolver} to stub {@code resolveToDialRef}: the FILE/not-FILE
 * decision ({@link DialFileRefResolver#isFileReference}) is real logic, not a collaborator call to stub, so it is
 * delegated to a real instance while every other method stays mocked.
 */
public final class DialFileRefResolverStubs {

    private DialFileRefResolverStubs() {}

    /**
     * Makes {@code isFileReference} on the given mock behave like the real implementation.
     *
     * @return the same mock, for fluent use in {@code setUp}
     */
    public static DialFileRefResolver withRealFileDetection(DialFileRefResolver mock) {
        final DialFileRefResolver real = new DialFileRefResolver(null, null, new BindingTypeResolver());
        lenient()
                .when(mock.isFileReference(any(), any(), any()))
                .thenAnswer(invocation -> real.isFileReference(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        return mock;
    }
}
