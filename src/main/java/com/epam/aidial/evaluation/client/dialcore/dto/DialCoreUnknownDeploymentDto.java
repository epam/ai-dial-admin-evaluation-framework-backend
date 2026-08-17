package com.epam.aidial.evaluation.client.dialcore.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Placeholder deployment payload used as {@code defaultImpl} for {@link DialCoreDeploymentDto}
 * when the {@code object} discriminator is missing or holds a value not among the known
 * "model" | "application" | "toolset" types. Deserializing to this type instead of throwing
 * preserves the base fields (including the raw {@code object} value) so callers can log and
 * skip the entry rather than failing the whole deployment list fetch.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class DialCoreUnknownDeploymentDto extends DialCoreDeploymentDto {}
