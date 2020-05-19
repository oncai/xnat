/*
 * web: org.nrg.xnat.services.system.HostInfoService
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

package org.nrg.xnat.tracking.services;

import org.nrg.framework.exceptions.NotFoundException;
import org.nrg.framework.orm.hibernate.BaseHibernateService;
import org.nrg.xnat.tracking.entities.EventTrackingDataPojo;
import org.nrg.xnat.tracking.entities.EventTrackingData;

/**
 * Provides information about the current host.
 */
public interface EventTrackingDataService extends BaseHibernateService<org.nrg.xnat.tracking.entities.EventTrackingData> {
    /**
     * Get EventListenerData payload by key
     * @param key the key
     * @return the payload
     * @throws NotFoundException if no event listener data exists for this key
     */
    String getPayloadByKey(final String key) throws NotFoundException;

    /**
     * Get EventListenerData entity by key
     * @param key the key
     * @return the entity
     * @throws NotFoundException if no event listener data exists for this key
     */
    org.nrg.xnat.tracking.entities.EventTrackingData findByKey(final String key) throws NotFoundException;

    /**
     * Get EventListenerData entity by key
     * @param key the key
     * @return the entity
     */
    EventTrackingData findOrCreateByKey(final String key);

    /**
     * Get EventListenerData entity by key
     * @param key the key
     * @return the pojo
     * @throws NotFoundException if no event listener data exists for this key
     */
    EventTrackingDataPojo getPojoByKey(final String key) throws NotFoundException;

    /**
     * Create new EventListenerData entity with key
     * @param key the key
     */
    void createWithKey(String key);
}