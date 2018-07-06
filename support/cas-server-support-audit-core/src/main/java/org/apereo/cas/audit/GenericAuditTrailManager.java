package org.apereo.cas.audit;

import org.apereo.inspektr.audit.AuditActionContext;
import org.apereo.inspektr.audit.AuditTrailManager;

import java.time.LocalDate;
import java.util.Set;

public interface GenericAuditTrailManager extends AuditTrailManager {
    Set<? extends AuditActionContext> getGenericAuditRecordsSince(LocalDate localDate);
}
