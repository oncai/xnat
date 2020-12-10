package org.nrg.xnat.eventservice.events;

import org.nrg.framework.event.XnatEventServiceEvent;
import org.nrg.xdat.model.XnatSubjectdataI;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@XnatEventServiceEvent(name="SubjectEvent")
public class SubjectEvent extends CombinedEventServiceEvent<XnatSubjectdataI> {

    public enum Status {CREATED, DELETED};

    public SubjectEvent(){};

    public SubjectEvent(final XnatSubjectdataI payload, final String eventUser, final Status status, final String projectId) {
        super(payload, eventUser, status, projectId, (payload != null ? payload.getXSIType() : null));
    }

    @Override
    public String getDisplayName() {
        return "Subject Event";
    }

    @Override
    public String getDescription() {
        return "Subject created, updated, or deleted.";
    }

    @Override
    public Boolean isPayloadXsiType() {
        return true;
    }

    @Override
    public List<String> getStatiStates() { return Arrays.stream(Status.values()).map(Status::name).collect(Collectors.toList()); }

}
