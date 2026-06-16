package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;

public interface RequestBodySerializer {

    boolean supports(ResolvedBodyDto body);

    SerializedBody serialize(ResolvedBodyDto body);
}
