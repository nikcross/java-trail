package org.onestonesoup.javatrail.sample.domain;

public class DomainWorkflow {

    public void executeWorkflow() {
        DomainService service = new DomainService();
        service.handleRequest("sample");
    }
}
