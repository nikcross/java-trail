package org.onestonesoup.javatrail.sample.domain;

import org.onestonesoup.javatrail.sample.lib.ExternalLibraryHelper;

public class DomainRepository {

    public String fetchRecord(String reference) {
        return ExternalLibraryHelper.enrich(reference);
    }

    public void persistRecord(String reference) {
        if (reference != null && !reference.isEmpty()) {
            audit(reference);
        }
    }

    private void audit(String reference) {
        ExternalLibraryHelper.audit(reference);
    }
}
