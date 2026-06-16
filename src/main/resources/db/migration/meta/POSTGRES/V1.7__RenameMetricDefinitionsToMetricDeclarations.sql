-- Rename metric_definitions table to metric_declarations (align with design: MetricDeclaration = catalog entity)
ALTER TABLE metric_definitions RENAME TO metric_declarations;
