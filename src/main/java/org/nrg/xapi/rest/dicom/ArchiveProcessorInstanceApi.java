/*
 * web: org.nrg.xapi.rest.data.InvestigatorsApi
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

package org.nrg.xapi.rest.dicom;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.nrg.dcm.scp.DicomSCPInstance;
import org.nrg.dcm.scp.DicomSCPManager;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.exceptions.XapiException;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.entities.ArchiveProcessorInstance;
import org.nrg.xnat.helpers.ArchiveProcessorInstanceSummary;
import org.nrg.xnat.processor.services.ArchiveProcessorInstanceService;
import org.nrg.xnat.processors.ArchiveProcessor;
import org.nrg.xnat.processors.StudyRemappingArchiveProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;
import static org.nrg.xdat.security.helpers.AccessLevel.Authenticated;

@Api(description = "XNAT Data Archive Processor Instance API")
@XapiRestController
@RequestMapping(value = "/processors")
public class ArchiveProcessorInstanceApi extends AbstractXapiRestController {
    @Autowired
    public ArchiveProcessorInstanceApi(final ArchiveProcessorInstanceService service, final UserManagementServiceI userManagementService, final RoleHolder roleHolder, final DicomSCPManager manager, final List<ArchiveProcessor> processors) {
        super(userManagementService, roleHolder);
        _service = service;
        _processors = processors;
        _manager = manager;
    }

    @ApiOperation(value = "Get list of processor classes.", notes = "The processor classes function returns a list of all processor classes in the XNAT system.", response = String.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Returns a list of all of the processor classes."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred")})
    @XapiRequestMapping(value = "classes", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<String>> getProcessorClasses() {
        return new ResponseEntity<>(processorNames(), HttpStatus.OK);
    }

    @ApiOperation(value = "Creates a new site processor instance from the submitted attributes.", notes = "Returns the newly created site processor instance with the submitted attributes.", response = ArchiveProcessorInstance.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Returns the newly created site processor instance."),
            @ApiResponse(code = 403, message = "Insufficient privileges to create the submitted site processor instance."),
            @ApiResponse(code = 404, message = "The requested site processor instance wasn't found."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred.")})
    @XapiRequestMapping(value = "site/create", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.POST, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<ArchiveProcessorInstance> createSiteProcessor(@RequestBody final ArchiveProcessorInstance processor) throws Exception {
        if (StringUtils.isBlank(processor.getProcessorClass())) {
            _log.error("User {} tried to create site processor instance without a processor class.", getSessionUser().getUsername());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        else if(!processorNames().contains(processor.getProcessorClass())){
            throw new XapiException(HttpStatus.BAD_REQUEST, "The provided processor class does not exist.");
        }
        if (!StringUtils.equals(ArchiveProcessorInstance.SITE_SCOPE,processor.getScope())) {
            _log.error("User {} tried to create site processor instance with non-site scope.", getSessionUser().getUsername());
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        ArchiveProcessorInstance created = _service.create(processor);
        return new ResponseEntity<>(created, HttpStatus.OK);
    }

    @ApiOperation(value = "Updates the requested site processor instance from the submitted attributes.", notes = "Returns the updated site processor instance.", response = ArchiveProcessorInstance.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Returns the updated site processor instance."),
            @ApiResponse(code = 304, message = "The requested site processor is the same as the submitted site processor instance."),
            @ApiResponse(code = 403, message = "Insufficient privileges to edit the requested site processor instance."),
            @ApiResponse(code = 404, message = "The requested site processor instance wasn't found."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred.")})
    @XapiRequestMapping(value = "site/id/{instanceId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.PUT, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<ArchiveProcessorInstance> updateSiteProcessor(@PathVariable("instanceId") final long instanceId, @RequestBody final ArchiveProcessorInstance processor) throws Exception {
        ArchiveProcessorInstance existingProcessor = _service.findSiteProcessorById(instanceId);
        if (existingProcessor == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        boolean isDirty = false;
        // Only update fields that are actually included in the submitted data and differ from the original source.
        if (StringUtils.isNotBlank(processor.getLabel()) && !StringUtils.equals(processor.getLabel(), existingProcessor.getLabel())) {
            existingProcessor.setLabel(processor.getLabel());
            isDirty = true;
        }
        if (StringUtils.isNotBlank(processor.getScope()) && !StringUtils.equals(processor.getScope(), existingProcessor.getScope())) {
            existingProcessor.setScope(processor.getScope());
            isDirty = true;
        }
        if (processor.getProjectIdsList()!=null && !processor.getProjectIdsList().equals(existingProcessor.getProjectIdsList())) {
            existingProcessor.setProjectIdsList(processor.getProjectIdsList());
            isDirty = true;
        }
        if (processor.getScpWhitelist()!=null && !processor.getScpWhitelist().equals(existingProcessor.getScpWhitelist())) {
            existingProcessor.setScpWhitelist(processor.getScpWhitelist());
            isDirty = true;
        }
        if (processor.getScpBlacklist()!=null && !processor.getScpBlacklist().equals(existingProcessor.getScpBlacklist())) {
            existingProcessor.setScpBlacklist(processor.getScpBlacklist());
            isDirty = true;
        }
        if (processor.getPriority()!=existingProcessor.getPriority()) {
            existingProcessor.setPriority(processor.getPriority());
            isDirty = true;
        }
        if (StringUtils.isNotBlank(processor.getLocation()) && !StringUtils.equals(processor.getLocation(), existingProcessor.getLocation())) {
            existingProcessor.setLocation(processor.getLocation());
            isDirty = true;
        }
        if ((processor.getParameters()==null && existingProcessor.getParameters()!=null) || (processor.getParameters()!=null && existingProcessor.getParameters()==null) || ((processor.getParameters()!=null) && !processor.getParameters().equals(existingProcessor.getParameters()))) {
            existingProcessor.setParameters(processor.getParameters());
            isDirty = true;
        }
        if (StringUtils.isNotBlank(processor.getProcessorClass()) && !StringUtils.equals(processor.getProcessorClass(), existingProcessor.getProcessorClass())) {
            if(!processorNames().contains(processor.getProcessorClass())){
                throw new XapiException(HttpStatus.BAD_REQUEST, "The provided processor class does not exist.");
            }
            existingProcessor.setProcessorClass(processor.getProcessorClass());
            isDirty = true;
        }
        if (processor.isEnabled()!=existingProcessor.isEnabled()) {
            existingProcessor.setEnabled(processor.isEnabled());
            isDirty = true;
        }
        _service.update(existingProcessor);
        if (isDirty) {
            return new ResponseEntity<>(existingProcessor, HttpStatus.OK);
        }

        return new ResponseEntity<>(HttpStatus.NOT_MODIFIED);
    }

    @ApiOperation(value = "Deletes the requested site processor instance from the submitted attributes.", response = Boolean.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Site processor instance was successfully removed."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 403, message = "Insufficient privileges to edit the requested site processor instance."),
            @ApiResponse(code = 404, message = "The requested site processor instance wasn't found."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred.")})
    @XapiRequestMapping(value = "site/id/{instanceId}", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.DELETE, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<Boolean> deleteSiteProcessor(@PathVariable("instanceId") final long instanceId) throws Exception {
        ArchiveProcessorInstance existingProcessor = _service.findSiteProcessorById(instanceId);
        if (existingProcessor == null) {
            return new ResponseEntity<>(false, HttpStatus.NOT_FOUND);
        }
        try{
            _service.delete(instanceId);
            return new ResponseEntity<>(true, HttpStatus.OK);
        }
        catch(Throwable t){
            _log.error("An error occurred deleting the processor instance " + instanceId, t);
            return new ResponseEntity<>(false, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @ApiOperation(value = "Get list of site processor instances.", notes = "The site processors function returns a list of all site processor instances configured in the XNAT system.", response = ArchiveProcessorInstance.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Returns a list of all of the currently configured site processor instances."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred")})
    @XapiRequestMapping(value = "site/list", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<ArchiveProcessorInstance>> getAllSiteProcessors() {
        return new ResponseEntity<>(_service.getAllSiteProcessors(), HttpStatus.OK);
    }

    @ApiOperation(value = "Get list of enabled site processor instances.", notes = "The enabled site processors function returns a list of all enabled site processor instances configured in the XNAT system.", response = ArchiveProcessorInstance.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Returns a list of all of the currently enabled site processor instances."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred")})
    @XapiRequestMapping(value = "site/enabled", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<ArchiveProcessorInstance>> getAllEnabledSiteProcessors() {
        return new ResponseEntity<>(_service.getAllEnabledSiteProcessors(), HttpStatus.OK);
    }

    @ApiOperation(value = "Get basic information about all enabled site processor instances.", notes = "The enabled site processors summary information function returns a list of basic information for all enabled site processor instances configured in the XNAT system.", response = ArchiveProcessorInstanceSummary.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Returns a list of basic information for all of the currently enabled site processor instances."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred")})
    @XapiRequestMapping(value = "site/enabled/summary", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Authenticated)
    @ResponseBody
    public ResponseEntity<List<ArchiveProcessorInstanceSummary>> getAllEnabledSiteProcessorsSummaryInformation() {
        List<ArchiveProcessorInstanceSummary> summaries = new ArrayList<>();
        List<ArchiveProcessorInstance> fullInstances = _service.getAllEnabledSiteProcessors();
        for(ArchiveProcessorInstance instance: fullInstances){
            summaries.add(new ArchiveProcessorInstanceSummary(instance));
        }
        return new ResponseEntity<>(summaries, HttpStatus.OK);
    }

    @ApiOperation(value = "Get list of enabled site processor instances for specified SCP receiver.", notes = "The enabled site processors function returns a list of all enabled site processor instances configured in the XNAT system for this receiver. Receiver should be specified like aeTitle:port.", response = ArchiveProcessorInstance.class, responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "Returns a list of all of the currently enabled site processor instances for this receiver."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred")})
    @XapiRequestMapping(value = "site/enabled/receiver/{aeAndPort}", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<List<ArchiveProcessorInstance>> getAllEnabledSiteProcessorsForAe(@PathVariable("aeAndPort") final String aeAndPort) {
        return new ResponseEntity<>(_service.getAllEnabledSiteProcessorsForAe(aeAndPort), HttpStatus.OK);
    }

    @ApiOperation(value = "Returns whether the provided AE and port are able to remap the data.", response = Boolean.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Remapping successfully checked."),
            @ApiResponse(code = 401, message = "Must be authenticated to access the XNAT REST API."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred.")})
    @XapiRequestMapping(value = "site/canRemap/receiver/{aeAndPort}", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Authenticated)
    @ResponseBody
    public ResponseEntity<Boolean> receiverCanRemap(@PathVariable("aeAndPort") final String aeAndPort) throws Exception {
        try {
            String[] aePortArray = aeAndPort.split(":");
            final DicomSCPInstance scpInstance = _manager.getDicomSCPInstance(aePortArray[0], Integer.parseInt(aePortArray[1]));
            if(scpInstance!=null && scpInstance.getCustomProcessing()){
                List<ArchiveProcessorInstance> processorInstances = _service.getAllEnabledSiteProcessorsForAe(aeAndPort);
                for(ArchiveProcessorInstance processorInstance : processorInstances){
                    try{
                        String procClass = processorInstance.getProcessorClass();
                        if(!StringUtils.isBlank(procClass)){
                            Class<?> cls = Class.forName(procClass);
                            if(StudyRemappingArchiveProcessor.class.isAssignableFrom(cls)){
                                return new ResponseEntity<>(true, HttpStatus.OK);
                            }
                        }
                    }
                    catch(Exception e){
                        _log.error("Failed to determine whether processor instance for a SCP receiver is able to remap.", e);
                    }
                }
            }
        }
        catch(Exception e){
            _log.error("Failed to determine whether SCP receiver is able to remap.", e);
        }
        return new ResponseEntity<>(false, HttpStatus.OK);
    }

    @ApiOperation(value = "Get the requested site processor instance by ID.", notes = "Returns the requested site processor instance.", response = ArchiveProcessorInstance.class)
    @ApiResponses({@ApiResponse(code = 200, message = "Returns the requested site processor instance."),
            @ApiResponse(code = 404, message = "The requested site processor instance wasn't found."),
            @ApiResponse(code = 500, message = "An unexpected or unknown error occurred.")})
    @XapiRequestMapping(value = "site/id/{instanceId}", produces = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET, restrictTo = Admin)
    @ResponseBody
    public ResponseEntity<ArchiveProcessorInstance> getSiteProcessor(@PathVariable("instanceId") final long instanceId) {
        ArchiveProcessorInstance processor = _service.findSiteProcessorById(instanceId);

        if (processor!=null) {
            return new ResponseEntity<>(processor, HttpStatus.OK);
        }

        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    private List<String> processorNames(){
        if(processorNames==null) {
            processorNames = new ArrayList<>();
            for (ArchiveProcessor proc : _processors) {
                processorNames.add(proc.getClass().getName());

            }
        }
        return processorNames;
    }

    private List<String> processorNames = null;

    private static final Logger _log = LoggerFactory.getLogger(ArchiveProcessorInstanceApi.class);

    private final ArchiveProcessorInstanceService _service;

    private final List<ArchiveProcessor> _processors;

    private final DicomSCPManager _manager;
}
