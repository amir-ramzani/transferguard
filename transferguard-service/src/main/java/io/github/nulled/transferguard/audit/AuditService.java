package io.github.nulled.transferguard.audit;

import io.github.nulled.transferguard.common.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> list(String entity, int page, int size) {
        return new PageResponse<>(page, size, repository.count(entity), repository.page(entity, page, size));
    }
}
