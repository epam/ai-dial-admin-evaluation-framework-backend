package com.epam.aidial.evaluation.architectural;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * Enforces the {@code @McpTool} method shape (D-F10) for every tool group under
 * {@code mcp.tools..}: return type, per-parameter {@code @McpToolParam} description, allowed
 * parameter types, and {@code snake_case} tool names. Created once {@link
 * com.epam.aidial.evaluation.mcp.tools.deployment.DeploymentTools} exists (group 3), so the rule
 * is never checked against an empty set.
 */
@DisplayName("MCP tool method conventions")
class McpToolConventionTest {

    private static final String MCP_TOOLS_PACKAGE = "com.epam.aidial.evaluation.mcp.tools";
    private static final String MCP_MODEL_PACKAGE = "com.epam.aidial.evaluation.mcp.model";
    private static final String TOOL_NAME_PATTERN = "^[a-z][a-z0-9_]*$";

    private static final Set<Class<?>> ALLOWED_SCALAR_PARAMETER_TYPES =
            Set.of(String.class, Boolean.class, Integer.class, Long.class);

    private static final JavaClasses CLASSES = new ClassFileImporter().importPackages(MCP_TOOLS_PACKAGE);

    private static final DescribedPredicate<JavaMethod> ARE_MCP_TOOL_METHODS =
            DescribedPredicate.describe("are annotated with @McpTool", method -> method.isAnnotatedWith(McpTool.class));

    @Test
    @DisplayName("every @McpTool method returns McpSchema.CallToolResult")
    void everyMcpToolMethodReturnsCallToolResult() {
        assertThat(mcpToolMethodCount()).isPositive();

        ArchRule rule = methods()
                .that(ARE_MCP_TOOL_METHODS)
                .should(new ArchCondition<JavaMethod>("return McpSchema.CallToolResult") {
                    @Override
                    public void check(JavaMethod method, com.tngtech.archunit.lang.ConditionEvents events) {
                        boolean satisfied = method.getRawReturnType().isEquivalentTo(McpSchema.CallToolResult.class);
                        events.add(new SimpleConditionEvent(
                                method,
                                satisfied,
                                method.getFullName() + " returns "
                                        + method.getRawReturnType().getName()));
                    }
                });

        rule.check(CLASSES);
    }

    @Test
    @DisplayName("every @McpTool method's parameters carry @McpToolParam with a non-blank description")
    void everyMcpToolParameterHasMcpToolParamWithNonBlankDescription() {
        assertThat(mcpToolMethodCount()).isPositive();

        ArchRule rule = methods()
                .that(ARE_MCP_TOOL_METHODS)
                .should(
                        new ArchCondition<JavaMethod>(
                                "have every parameter annotated with @McpToolParam with a non-blank description") {
                            @Override
                            public void check(JavaMethod method, com.tngtech.archunit.lang.ConditionEvents events) {
                                for (JavaParameter parameter : method.getParameters()) {
                                    McpToolParam annotation = parameter.isAnnotatedWith(McpToolParam.class)
                                            ? parameter.getAnnotationOfType(McpToolParam.class)
                                            : null;
                                    boolean satisfied = annotation != null
                                            && !annotation.description().isBlank();
                                    events.add(new SimpleConditionEvent(
                                            method,
                                            satisfied,
                                            method.getFullName() + " parameter " + parameter.getIndex()
                                                    + " must carry @McpToolParam with a non-blank description"));
                                }
                            }
                        });

        rule.check(CLASSES);
    }

    @Test
    @DisplayName("every @McpTool method's parameters are String, Boolean, Integer, Long or a record in mcp.model")
    void everyMcpToolParameterTypeIsAllowed() {
        assertThat(mcpToolMethodCount()).isPositive();

        ArchRule rule = methods()
                .that(ARE_MCP_TOOL_METHODS)
                .should(
                        new ArchCondition<JavaMethod>(
                                "have every parameter typed String, Boolean, Integer, Long, or a record in "
                                        + MCP_MODEL_PACKAGE) {
                            @Override
                            public void check(JavaMethod method, com.tngtech.archunit.lang.ConditionEvents events) {
                                for (JavaParameter parameter : method.getParameters()) {
                                    boolean satisfied = ALLOWED_SCALAR_PARAMETER_TYPES.stream()
                                                    .anyMatch(parameter.getRawType()::isEquivalentTo)
                                            || (parameter.getRawType().isRecord()
                                                    && parameter
                                                            .getRawType()
                                                            .getPackageName()
                                                            .equals(MCP_MODEL_PACKAGE));
                                    events.add(new SimpleConditionEvent(
                                            method,
                                            satisfied,
                                            method.getFullName() + " parameter " + parameter.getIndex()
                                                    + " has disallowed type "
                                                    + parameter.getRawType().getName()));
                                }
                            }
                        });

        rule.check(CLASSES);
    }

    @Test
    @DisplayName("every @McpTool tool name is snake_case")
    void everyMcpToolNameIsSnakeCase() {
        assertThat(mcpToolMethodCount()).isPositive();

        ArchRule rule = methods()
                .that(ARE_MCP_TOOL_METHODS)
                .should(new ArchCondition<JavaMethod>("have a snake_case @McpTool#name()") {
                    @Override
                    public void check(JavaMethod method, com.tngtech.archunit.lang.ConditionEvents events) {
                        String name = method.getAnnotationOfType(McpTool.class).name();
                        boolean satisfied = name.matches(TOOL_NAME_PATTERN);
                        events.add(new SimpleConditionEvent(
                                method,
                                satisfied,
                                method.getFullName() + "'s @McpTool#name() '" + name + "' is not snake_case"));
                    }
                });

        rule.check(CLASSES);
    }

    private static long mcpToolMethodCount() {
        return CLASSES.stream()
                .flatMap(clazz -> clazz.getMethods().stream())
                .filter(ARE_MCP_TOOL_METHODS)
                .count();
    }
}
