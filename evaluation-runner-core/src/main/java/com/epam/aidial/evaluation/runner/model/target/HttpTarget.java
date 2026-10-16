package com.epam.aidial.evaluation.runner.model.target;

/** A target invoked over HTTP through DIAL Core: either a deployment or a global route. */
public sealed interface HttpTarget extends SuiteTarget permits DeploymentTarget, RouteTarget {}
