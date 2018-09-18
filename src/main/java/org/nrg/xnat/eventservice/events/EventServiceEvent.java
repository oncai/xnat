package org.nrg.xnat.eventservice.events;

import org.nrg.framework.event.EventI;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface EventServiceEvent<ObjectT> extends EventI {

    // Get descriptive unique identifier for event
    String getType();

    String getDisplayName();
    String getDescription();
     // Get payload object
    ObjectT getObject();
     // Get class type of payload object
    Class getObjectClass();
     // Get string describing XNAT data type, e.g. xnat:imageSessionData
    String getPayloadXnatType();
     // Is the value returned by getPayloadXnatType() a known xsi type?
    Boolean isPayloadXsiType();
    // Event is expected to be triggered with JSON representation of (at least some of) the payload object
    Boolean filterablePayload();
    // JSON String representation of payload - used for Reactor filtering
    Object getPayloadSignatureObject();
    // Get the user triggering the event
    String getUser();
     // Get the time of event triggering
    Date getEventTimestamp();
     // Get the event object UUID (generated at instantiation)
    UUID getEventUUID();
    //Get the possible status states, e.g. created, updated, deleted
    List<String> getStatiStates();
    //Get the status of the current triggered event
    Enum getCurrentStatus();
    //Get the projectId of the current triggered event (returns null if no Project association)
    String getProjectId();

    String toString();
}
