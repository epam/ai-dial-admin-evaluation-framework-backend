package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.dto.ResolvedBodyDto;

public interface RequestBodySerializer {

    boolean supports(ResolvedBodyDto body);

    SerializedBody serialize(ResolvedBodyDto body);
}
