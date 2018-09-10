/*
 * web: eventServiceUi.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * Site-wide Admin UI functions for the Event Service
 */

console.log('xnat/admin/eventServiceUi.js');

var XNAT = getObject(XNAT || {});

(function(factory){
    if (typeof define === 'function' && define.amd) {
        define(factory);
    }
    else if (typeof exports === 'object') {
        module.exports = factory();
    }
    else {
        return factory();
    }
}(function(){

    /* ================ *
     * GLOBAL FUNCTIONS *
     * ================ */

    function spacer(width){
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    function errorHandler(e, title, closeAll){
        console.log(e);
        title = (title) ? 'Error Found: '+ title : 'Error';
        closeAll = (closeAll === undefined) ? true : closeAll;
        var errormsg = (e.statusText) ? '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p><p>' + e.responseText + '</p>' : e;
        XNAT.dialog.open({
            width: 450,
            title: title,
            content: errormsg,
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true,
                    action: function(){
                        if (closeAll) {
                            xmodal.closeAll();

                        }
                    }
                }
            ]
        });
    }

    function titleCase(string){
        var words = string.split(' ');
        words.forEach(function(word,i){
            words[i] = word[0].toUpperCase() + word.slice(1).toLowerCase();
        });
        return words.join(' ');
    }


    /* ====================== *
     * Site Admin UI Controls *
     * ====================== */

    var eventServicePanel,
        undefined,
        rootUrl = XNAT.url.rootUrl,
        restUrl = XNAT.url.restUrl,
        csrfUrl = XNAT.url.csrfUrl;

    XNAT.admin =
        getObject(XNAT.admin || {});

    XNAT.admin.eventServicePanel = eventServicePanel =
        getObject(XNAT.admin.eventServicePanel || {});

    eventServicePanel.xsiTypes = [
        'xnat:projectData',
        'xnat:subjectData',
        'xnat:imageSessionData',
        'xnat:imageScanData'
    ];

    eventServicePanel.events = {};
    eventServicePanel.actions = {};

    function getProjectListUrl(){
        return restUrl('/data/projects?format=json');
    }

    function getEventActionsUrl(projectId,eventType){
        var path = (eventType) ?
            '/xapi/events/actionsbyevent?event-type='+eventType :
            '/xapi/events/allactions';

        if (eventType && projectId) path += '&project='+projectId;
        return restUrl(path);
    }

    function getEventSubscriptionUrl(id){
        id = id || false;
        var path = (id) ? '/xapi/events/subscription/'+id : '/xapi/events/subscriptions';
        return restUrl(path);
    }

    function setEventSubscriptionUrl(id,appended){
        id = id || false;
        var path = (id) ? '/xapi/events/subscription/'+id : '/xapi/events/subscription';
        appended = (appended) ? '/'+appended : '';
        return csrfUrl(path + appended);
    }

    eventServicePanel.getProjects = function(callback){
        callback = isFunction(callback) ? callback : function(){};
        return XNAT.xhr.getJSON({
            url: getProjectListUrl(),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could not retrieve projects');
            }
        })
    };

    eventServicePanel.getEvents = function(callback){
        callback = isFunction(callback) ? callback : function(){};

        return XNAT.xhr.getJSON({
            url: XNAT.url.restUrl('/xapi/events/events'),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could not retrieve events');
            }
        })
    };

    eventServicePanel.getSubscriptions = function(callback){
        callback = isFunction(callback) ? callback : function(){};

        return XNAT.xhr.getJSON({
            url: getEventSubscriptionUrl(),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could not retrieve event subscriptions');
            }
        })
    };

    eventServicePanel.getActions = function(opts,callback){
        var project = (opts) ? opts.project : false;
        var xsiType = (opts) ? opts.xsiType : false;

        callback = isFunction(callback) ? callback : function(){};

        return XNAT.xhr.getJSON({
            url: getEventActionsUrl(project,xsiType),
            success: function(data){
                if (data) {
                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could not retrieve event actions');
            }
        })
    };


    /* -------------------------- *
     * Subscription Display Table *
     * -------------------------- */
    eventServicePanel.subscriptionTable = function(){
        // initialize the table
        var subTable = XNAT.table({
            addClass: 'xnat-table compact',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        });

        // add table header row
        subTable.tr()
            .th({ addClass: 'left', html: '<b>Name</b>' })
            .th('<b>Project</b>')
            .th('<b>Trigger Event</b>')
            .th('<b>Action</b>')
            .th('<b>Created By</b>')
            .th('<b>Enabled</b>')
            .th({ style: { width: '125px' }, html: '<b>Action</b>' });

        /* Formatted table cells */
        function subscriptionNiceLabel(label,id){
            return spawn('a',{
                href: '#!',
                style: { 'font-weight': 'bold' },
                onclick: function(e){
                    e.preventDefault();
                    eventServicePanel.editSubscription('Edit',id);
                }
            }, label);
        }
        function displayProjects(projects){
            if (isArray(projects) && projects.length) {
                return projects.join(', ');
            }
            else {
                return 'All Projects';
            }
        }
        function eventNiceName(subscription){
            var eventId = subscription['event-filter']['event-type'];
            return eventServicePanel.events[eventId]['display-name'] + ': ' + titleCase(subscription['event-filter']['status']);
        }
        function actionNiceName(actionKey){
            return (eventServicePanel.actions[actionKey]) ?
                eventServicePanel.actions[actionKey]['display-name'] :
                actionKey;
        }
        function subscriptionEnabledCheckbox(subscription){
            var enabled = !!subscription.active;
            var ckbox = spawn('input.subscription-enabled', {
                type: 'checkbox',
                checked: enabled,
                value: 'true',
                onchange: function(){
                    eventServicePanel.toggleSubscription(subscription.id, this);
                }
            });

            return spawn('div.center', [
                spawn('label.switchbox|title=' + subscription.name, [
                    ckbox,
                    ['span.switchbox-outer', [['span.switchbox-inner']]]
                ])
            ]);
        }
        function editSubscriptionButton(subscription){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    eventServicePanel.editSubscription('Edit',subscription.id);
                },
                title: 'Edit'
            }, [ spawn('span.fa.fa-pencil') ]);
        }
        function cloneSubscriptionButton(subscription){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    eventServicePanel.editSubscription('Clone',subscription.id);
                },
                title: 'Duplicate'
            }, [ spawn('span.fa.fa-clone') ]);
        }
        function deleteSubscriptionButton(subscription){
            return spawn('button.btn.sm', {
                onclick: function(e){
                    e.preventDefault();
                    eventServicePanel.deleteSubscriptionConfirmation(subscription);
                },
                title: 'Delete'
            }, [ spawn('span.fa.fa-trash') ]);
        }

        eventServicePanel.getSubscriptions().done(function(data){
            if (data.length) {
                data = data.sort(function(a,b){ return (a.name > b.name) ? 1 : -1 });

                data.forEach(function(subscription){
                    subTable.tr({ addClass: (subscription.valid) ? 'valid' : 'invalid', id: 'event-subscription-'+subscription.id, data: { id: subscription.id } })
                        .td([ subscriptionNiceLabel(subscription.name,subscription.id) ])
                        .td([ displayProjects(subscription['event-filter']['project-ids']) ])
                        .td([ eventNiceName(subscription) ])
                        .td([ actionNiceName(subscription['action-key']) ])
                        .td(subscription['subscription-owner'])
                        .td([ subscriptionEnabledCheckbox(subscription) ])
                        .td({ addClass: 'center' },[ editSubscriptionButton(subscription), spacer(4), cloneSubscriptionButton(subscription), spacer(4), deleteSubscriptionButton(subscription) ])
                })
            }
            else {
                subTable.tr().td({ colSpan: '7', html: 'No event subscriptions have been created' })
            }

        });

        eventServicePanel.$table = $(subTable.table);

        return subTable.table;
    };

    /* ---------------------------------- *
     * Create, Edit, Delete Subscriptions *
     * ---------------------------------- */

    var emptyOptionObj = {
        html: '<option selected></option>'
    };
    var createFormObj = {
        kind: 'panel.form',
        id: 'edit-subscription-form',
        header: false,
        footer: false,
        element: {
            style: { border: 'none', margin: '0' }
        },
        contents: {
            subName: {
                kind: 'panel.input.text',
                name: 'name',
                label: 'Event Subscription Label',
                validation: 'not-empty',
                order: 10
            },
            subId: {
                kind: 'panel.input.hidden',
                name: 'id',
                element: {
                    disabled: 'disabled'
                }
            },
            subEventSelector: {
                kind: 'panel.select.single',
                name: 'event-selector',
                label: 'Select Event',
                id: 'subscription-event-selector',
                // element: emptyOptionObj,
                order: 20
            },
            subEventType: {
                kind: 'panel.input.hidden',
                name: 'event-type',
                id: 'subscription-event-type'
            },
            subEventStatus: {
                kind: 'panel.input.hidden',
                name: 'status',
                id: 'subscription-event-status'
            },
            subProjSelector: {
                kind: 'panel.select.single',
                name: 'project-id',
                label: 'Select Project',
                id: 'subscription-project-selector',
                element: {
                    html: '<option selected value="">Any Project</option>'
                },
                order: 30
            },
            subActionSelector: {
                kind: 'panel.select.single',
                name: 'action-key',
                label: 'Select Action',
                id: 'subscription-action-selector',
                element: emptyOptionObj,
                description: 'Available actions are dependent on your project and xsiType selections',
                order: 40
            },
            subActionPreview: {
                tag: 'div#subscription-action-preview.panel-element',
                element: {
                    style: {
                        display: 'none'
                    }
                },
                contents: {
                    subActionPreviewPane: {
                        tag: 'div.preview-pane.panel-element',
                        element: {
                            style: {
                                border: '1px dotted #ccc',
                                'margin-bottom': '2em',
                                padding: '1em 1em 0'
                            }
                        },
                        contents: {
                            subAttributeLabel: {
                                tag: 'label.element-label',
                                content: 'Attributes'
                            },
                            subAttributePreview: {
                                tag: 'div.element-wrapper',
                                contents: {
                                    subAttributePreviewTextarea: {
                                        tag: 'p',
                                        content: '<textarea id="sub-action-attribute-preview" class="disabled" disabled="disabled" readonly></textarea><br>'
                                    },
                                    subAttributeEditButton: {
                                        tag: 'p',
                                        content: '<button id="set-sub-action-attributes">Set Attributes</button>'
                                    }
                                }
                            },
                            subAttributeClear: {
                                tag: 'div.clear.clearfix'
                            }
                        }
                    }
                },
                order: 41
            },
            subActionAttributes: {
                kind: 'panel.input.hidden',
                name: 'attributes',
                id: 'subscription-action-attributes'
            },
            subActionInherited: {
                kind: 'panel.input.hidden',
                name: 'inherited-action',
                id: 'subscription-inherited-action'
            },
            subFilter: {
                kind: 'panel.input.text',
                name: 'payload-filter',
                label: 'Event Payload Filter',
                description: 'Optional. Enter filter in JSON path notation without enclosing brackets, e.g. <pre style="margin-top:0">(@.xsiType == "xnat:mrScanData")</pre>',
                order: 50
            },
            subUserProxy: {
                kind: 'panel.input.switchbox',
                name: 'act-as-event-user',
                label: 'Perform Action As:',
                onText: 'Action is performed as the user who initiates the event',
                offText: 'Action is performed as you (the subscription owner)',
                value: 'true',
                order: 60
            },
            subActive: {
                kind: 'panel.input.switchbox',
                name: 'active',
                label: 'Status',
                onText: 'Enabled',
                offText: 'Disabled',
                value: 'true',
                order: 70
            }
        }
    };

    // populate the hidden Status field based on selected event
    function setEventStatus($element){
        var $form = $element.parents('form');
        var status = $element.find('option:selected').data('status');
        var eventType = $element.find('option:selected').data('event-type');
        $form.find('input[name=status]').val(status);
        $form.find('input[name=event-type]').val(eventType);
    }

    // populate the Action Select menu based on selected project and event (which provides xsitype)
    function findActions($element){
        var $form = $element.parents('form');
        var project = $form.find('select[name=project-id]').find('option:selected').val();
        var xsiType = $form.find('select[name=event-selector]').find('option:selected').data('xsitype');
        var eventType = $form.find('select[name=event-selector]').find('option:selected').data('event-type');
        var inheritedAction = $form.find('input[name=inherited-action]').val(); // hack to stored value for edited subscription
        var actionSelector = $form.find('select[name=action-key]');
        var url;

        if (project && actionSelector) {
            url = getEventActionsUrl(project,eventType);
        }
        else if (actionSelector) {
            url = getEventActionsUrl(false,eventType);
        }
        else {
            url = getEventActionsUrl();
        }


        XNAT.xhr.get({
            url: url,
            success: function(data){
                actionSelector
                    .empty()
                    .append(spawn('option', { selected: true }));
                if (data.length){
                    data.forEach(function(action){
                        var selected = false;
                        if (inheritedAction.length && action['action-key'] === inheritedAction) {
                            // if we're editing an existing subscription, we'll know the action before this select menu knows which actions exist.
                            // get the stored value and mark this option selected if it matches, then clear the stored value.
                            selected='selected';
                            // $form.find('input[name=inherited-action]').val('');
                        }

                        actionSelector.append( spawn('option', { value: action['action-key'], selected: selected }, action['display-name'] ))
                        // if the action has attributes, add them to the global actions object
                        if (action['attributes']) {
                            eventServicePanel.actions[action['action-key']].attributes = action['attributes'];
                        }
                    });
                }
            }
        })
    }

    // populate or hide the Action Attributes selector depending on whether it is required by the selected action
    function getActionAttributes($element){
        var $form = $element.parents('form');
        var actionId = $element.find('option:selected').val();
        if (actionId) {
            if (eventServicePanel.actions[actionId].attributes && eventServicePanel.actions[actionId].attributes !== {}) {
                $form.find('#subscription-action-preview').slideDown(300);
            }
            else {
                $form.find('#subscription-action-preview').slideUp(300);
            }
        }
    }

    // display Action Attributes after editing
    function setActionAttributes($element){
        var $form = $element.parents('form');
        var actionKey = $form.find('select[name=action-key]').find('option:selected').val();
        var storedAttributes = $form.find('#sub-action-attribute-preview').html();
        var genericAttributes = eventServicePanel.actions[actionKey].attributes;

        var attributesObj = Object.assign({}, genericAttributes);

        if (storedAttributes.length) {
            storedAttributes = JSON.parse(storedAttributes);

            // overwrite any generic values with saved values
            Object.keys(storedAttributes).forEach(function(key,val){
                attributesObj[key] = val;
            });

            // if any generic values were ignored, zero them out
            Object.keys(genericAttributes).forEach(function(key){
                if (storedAttributes[key] === undefined || storedAttributes[key].length === 0) {
                    attributesObj[key] = '';
                }
            })
        }

        eventServicePanel.enterAttributesDialog(attributesObj,genericAttributes,eventServicePanel.actions[actionKey]['display-name']);
    }
    function renderAttributeInput(name,props,opts){

        var obj = {
            label: name,
            name: name,
            description: props.description
        };

        if (props.required) obj.validation = 'required';
        obj.value = props['default-value'] || '';

        var el;

        if (eventServicePanel.subscriptionAttributes) {
            var presetAttributes = (typeof eventServicePanel.subscriptionAttributes === "string") ?
                JSON.parse(eventServicePanel.subscriptionAttributes) :
                eventServicePanel.subscriptionAttributes;
            obj.value = presetAttributes[name];
        }

        switch (props.type){
            case 'boolean':
                obj.onText = 'On';
                obj.offText = 'Off';
                obj.value = obj.value || 'true';

                // allow provided opts to override these defaults
                if (opts) obj = Object.assign( obj, opts );
                el = XNAT.ui.panel.input.switchbox(obj);
                break;

            default:
                if (opts) obj = Object.assign( obj, opts );
                el = XNAT.ui.panel.input.text(obj);
        }

        return el;
    }

    eventServicePanel.enterAttributesDialog = function(attributesObj,genericAttributes,actionLabel){
        var inputElements;
        if (Object.keys(attributesObj).length > 0) {
            inputElements = [];
            Object.keys(attributesObj).forEach(function(name){
                var opts = {};
                var props = attributesObj[name];

                // check to see if supplied attribute is a part of the basic set of supported attributes
                if (Object.keys(genericAttributes).indexOf(name) < 0) opts = { addClass: 'invalid', description: 'This parameter is not natively supported by this action and may be ignored' };

                inputElements.push( renderAttributeInput(name,props,opts) );
            });
            inputElements = spawn('!',inputElements);
        }
        else {
            return false
        }
        eventServicePanel.subscriptionAttributes = "";
        XNAT.ui.dialog.open({
            width: 450,
            title: false,
            content: '<h3 style="font-weight: normal;">Enter Attributes for '+ titleCase(actionLabel) +'</h3>' +
                '<form class="xnat-form-panel panel panel-default" id="attributes-form" style="border: none; padding-top: 1em;">' +
                '<div id="attributes-elements-container"></div></form>',
            beforeShow: function(obj){
                var $container = obj.$modal.find('#attributes-elements-container');
                $container.append( inputElements );
            },
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true,
                    action: function(obj){
                        var $form = obj.$modal.find('form');
                        eventServicePanel.subscriptionAttributes = JSON.stringify($form);
                        $(document).find('#sub-action-attribute-preview').html(eventServicePanel.subscriptionAttributes);
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        })
    };

    eventServicePanel.modifySubscription = function(action,subscription){
        var projs = eventServicePanel.projects;
        subscription = subscription || false;
        action = action || 'Create';

        XNAT.ui.dialog.open({
            title: action + ' Event Subscription',
            width: 600,
            content: '<div id="subscription-form-container"></div>',
            beforeShow: function(obj){
                var $container = obj.$modal.find('#subscription-form-container');
                XNAT.spawner.spawn({ form: createFormObj }).render($container);

                var $form = obj.$modal.find('form');

                if (projs.length) {
                    projs.forEach(function(project){
                        $form.find('#subscription-project-selector')
                            .append(spawn(
                                'option',
                                { value: project.ID },
                                project['secondary_ID']
                            ));
                    });
                }
                else {
                    $form.find('#subscription-project-selector').parents('.panel-element').hide();
                }

                Object.keys(eventServicePanel.events).forEach(function(event){
                    var thisEvent = eventServicePanel.events[event];

                    var optGroup = [];
                    thisEvent.statuses.forEach(function(status){
                        optGroup.push(spawn(
                                'option',
                                { value: event+':'+status, data: {
                                    xsitype: thisEvent['xnat-type'],
                                    status: status,
                                    'event-type': event
                                }},
                                thisEvent['display-name'] + ' -- ' + titleCase(status)
                            ));
                    });
                    $form.find('#subscription-event-selector')
                        .append(spawn('optgroup', optGroup));
                });

                if (subscription){
                    // Prepopulate / preselect form fields if we are editing an existing subscription.
                    // This involves a bit of manipulation of object properties between the subscription and the form elements

                    var subscriptionData = subscription;

                    eventServicePanel.subscriptionAttributes = subscription.attributes;

                    subscriptionData['project-id'] = subscription['event-filter']['project-ids'][0];
                    subscriptionData['event-type'] = subscription['event-filter']['event-type'];
                    subscriptionData['status'] = subscription['event-filter']['status'];
                    subscriptionData['event-selector'] = subscription['event-filter']['event-type'] + ':' + subscription['event-filter']['status'];
                    subscriptionData['payload-filter'] = subscription['event-filter']['payload-filter'];
                    subscriptionData['inherited-action'] = subscription['action-key']; 

                    if (action.toLowerCase() === "clone") {
                        delete subscriptionData.name;
                        delete subscriptionData.id;
                        delete subscriptionData['registration-key'];
                    }
                    if (action.toLowerCase() === "edit") {
                        $form.find('input[name=id]').prop('disabled',false);
                    }

                    $form.setValues(subscriptionData); // sets values in inputs and selectors, which triggers the onchange listeners below. Action has to be added again after the fact.
                    findActions($form.find('#subscription-event-selector'));
                    $form.addClass((subscription.valid) ? 'valid' : 'invalid');

                    // custom set event selector

                    if (Object.keys(subscription.attributes).length) {
                        $form.find('#subscription-action-preview').show();
                        $form.find('#sub-action-attribute-preview').html( JSON.stringify(subscription.attributes) );
                    }
                }
                else delete eventServicePanel.subscriptionAttributes;

                // Create form-specific event handlers, enable them after setValues() has run
                $form.off('change','select[name=project-id]').on('change','select[name=project-id]', function(){
                    findActions($(this));
                });
                $form.off('change','select[name=event-selector]').on('change','select[name=event-selector]', function(){
                    findActions($(this));
                    setEventStatus($(this));
                });
                $form.off('change','select[name=action-key]').on('change','select[name=action-key]', function(){
                    getActionAttributes($(this));
                });
                $form.off('click','#set-sub-action-attributes').on('click','#set-sub-action-attributes', function(e){
                    e.preventDefault();
                    setActionAttributes($(this));
                });
            },
            buttons: [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true,
                    action: function(obj){
                        // Convert form inputs to a parseable JSON object
                        // This also involves a conversion into the accepted JSON attribute hierarchy
                        var formData, jsonFormData = {}, projectArray = [];
                        obj.dialog$.find('form').serializeArray().map(function(x){jsonFormData[x.name] = x.value;});

                        if (eventServicePanel.subscriptionAttributes) {
                            jsonFormData.attributes = (typeof eventServicePanel.subscriptionAttributes === 'object') ?
                                eventServicePanel.subscriptionAttributes :
                                JSON.parse(eventServicePanel.subscriptionAttributes);
                        }
                        else {
                            jsonFormData.attributes = {};
                        }

                        jsonFormData['event-filter'] = {};

                        jsonFormData['event-filter']['event-type'] = jsonFormData['event-type'];
                        delete jsonFormData['event-type'];

                        jsonFormData['event-filter']['status'] = jsonFormData['status'];
                        delete jsonFormData['status'];

                        delete jsonFormData['inherited-action'];

                        if (jsonFormData['project-id']) {
                            projectArray.push(jsonFormData['project-id']);
                            jsonFormData['event-filter']['project-ids'] = projectArray;
                            delete jsonFormData['project-id'];
                        }
                        if (jsonFormData['payload-filter']) {
                            jsonFormData['event-filter']['payload-filter'] = jsonFormData['payload-filter'];
                            delete jsonFormData['payload-filter'];
                        } else {
                            jsonFormData['event-filter']['payload-filter'] = '';
                        }
                        if (!jsonFormData['active']) jsonFormData['active'] = false;
                        if (!jsonFormData['act-as-event-user']) jsonFormData['act-as-event-user'] = false;

                        formData = JSON.stringify(jsonFormData);

                        var url = (action.toLowerCase() === 'edit') ? setEventSubscriptionUrl(subscription.id) : setEventSubscriptionUrl();
                        var method = (action.toLowerCase() === 'edit') ? 'PUT' : 'POST';
                        var successMessages = {
                            'Create': 'Created new event subscription',
                            'Edit' : 'Edited event subscription',
                            'Clone' : 'Created new event subscription'
                        };

                        XNAT.xhr.ajax({
                            url: url,
                            data: formData,
                            method: method,
                            contentType: 'application/json',
                            success: function(){
                                XNAT.ui.banner.top(2000,successMessages[action],'success');
                                eventServicePanel.refreshSubscriptionList();
                            },
                            fail: function(e){
                                errorHandler(e,'Could not create event subscription')
                            }
                        })
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        })
    };

    eventServicePanel.editSubscription = function(action,subscriptionId) {
        action = action || "Edit";
        if (!subscriptionId) return false;

        XNAT.xhr.getJSON({
            url: getEventSubscriptionUrl(subscriptionId),
            success: function(subscriptionData){
                eventServicePanel.modifySubscription(action,subscriptionData);
            },
            fail: function(e){
                errorHandler(e,'Could not retrieve event subscription details');
            }
        })
    };

    eventServicePanel.toggleSubscription = function(id,selector){
        // if underlying checkbox has just been checked, take action to enable this subscription
        var enableMe = $(selector).prop('checked');
        if (enableMe){
            eventServicePanel.enableSubscription(id);
        }
        else {
            eventServicePanel.disableSubscription(id);
        }
    };

    eventServicePanel.enableSubscription = function(id,refresh){
        refresh = refresh || false;
        XNAT.xhr.ajax({
            url: setEventSubscriptionUrl(id,'/activate'),
            method: 'POST',
            success: function(){
                XNAT.ui.banner.top(2000,'Event subscription enabled','success');
                if (refresh) eventServicePanel.refreshSubscriptionList();
            },
            fail: function(e){
                errorHandler(e,'Could not enable event subscription')
            }
        })
    };
    eventServicePanel.disableSubscription = function(id,refresh){
        refresh = refresh || false;
        XNAT.xhr.ajax({
            url: setEventSubscriptionUrl(id,'/deactivate'),
            method: 'POST',
            success: function(){
                XNAT.ui.banner.top(2000,'Event subscription disabled','success');
                if (refresh) eventServicePanel.refreshSubscriptionList();
            },
            fail: function(e){
                errorHandler(e,'Could not disable event subscription');
            }
        })
    };

    eventServicePanel.deleteSubscriptionConfirmation = function(subscription){


        XNAT.ui.dialog.open({
            title: 'Confirm Deletion',
            width: 350,
            content: '<p>Are you sure you want to permanently delete the <strong>'+ escapeHTML(subscription.name) +'</strong> event subscription? This will also delete all event history items associated with this event. This operation cannot be undone. Alternately, you can just disable it.</p>',
            buttons: [
                {
                    label: 'Confirm Delete',
                    isDefault: true,
                    close: true,
                    action: function(){
                        eventServicePanel.deleteSubscription(subscription.id);
                        eventServicePanel.historyTable.refresh();
                    }
                },
                {
                    label: 'Disable',
                    close: true,
                    action: function(){
                        eventServicePanel.disableSubscription(subscription.id,true);
                    }
                },
                {
                    label: 'Cancel',
                    close: true
                }
            ]
        })
    };
    eventServicePanel.deleteSubscription = function(id){
        if (!id) return false;
        XNAT.xhr.ajax({
            url: getEventSubscriptionUrl(id),
            method: 'DELETE',
            success: function(){
                XNAT.ui.banner.top(2000,'Permanently deleted event subscription', 'success');
                eventServicePanel.refreshSubscriptionList();
            },
            fail: function(e){
                errorHandler(e, 'Could not delete event subscription');
            }
        })
    };

    /* browser event listeners */

    $(document).off('click','#create-new-subscription').on('click', '#create-new-subscription', function(e){
        // console.log(e);
        XNAT.admin.eventServicePanel.modifySubscription('Create');
    });

    /* ---------------------------------- *
     * Display Event Subscription History *
     * ---------------------------------- */

    var historyTable, historyData;

    XNAT.admin.eventServicePanel.historyTable = historyTable =
        getObject(XNAT.admin.eventServicePanel.historyTable || {});

    XNAT.admin.eventServicePanel.historyData = historyData =
        getObject(XNAT.admin.eventServicePanel.historyData || {});

    function viewHistoryDialog(e, onclose){
        e.preventDefault();
        var historyId = $(this).data('id') || $(this).closest('tr').prop('title');
        eventServicePanel.historyTable.viewHistory(historyId);
    }

    function getHistoryUrl(project,sub){
        var params = [];
        if (project) params.push('project='+project);
        if (sub) params.push('subscriptionid='+sub);
        var appended = (params.length) ? '?'+params.join('&') : '';

        return XNAT.url.restUrl('/xapi/events/delivered'+appended);
    }

    historyTable.getHistory = function(opts,callback){
        callback = isFunction(callback) ? callback : function(){};
        var project = (opts) ? opts.project : false;
        var subscription = (opts) ? opts.subscription : false;

        return XNAT.xhr.getJSON({
            url: getHistoryUrl(project,subscription),
            success: function(data){
                if (data.length){
                    data.forEach(function(entry){
                        historyData[entry.id] = entry;
                    });

                    return data;
                }
                callback.apply(this, arguments);
            },
            fail: function(e){
                errorHandler(e,'Could Not Get History');
            }
        })
    };
    //
    // function StringIndexOfFilter() {
    //     "use strict";
    //
    //     this.getFilterRegex = function (filterText) {
    //         return filterText;
    //     };
    // }
    //
    // var getFilterRegex = function (filterText) {
    //     return new StringIndexOfFilter().getFilterRegex(filterText);
    // };

    var addColumnFilters = function ($datatable, dataTableColumns) {
        var filterHeaderRowId = "filterHeaderRow";
        var datatableId = $datatable.prop('id');
        $datatable.find('thead').append('<tr id="' + filterHeaderRowId + '" class="filter">');

        dataTableColumns.forEach(function(column){
            if (column.mData) {
                var inputId = filterHeaderRowId + "Input" + i;
                jq("#" + filterHeaderRowId).append('<th class="noPointer"><input type="text" id="' + inputId + '" name="' + inputId + '" placeholder="Filter..." class="filter_init datatable-filter" /></th>');
            } else {
                jq("#" + filterHeaderRowId).append('<th class="noPointer"/>');
            }
        });

        var asInitVals = [];

        $datatable.find('thead input').each(function (i) {
            asInitVals[i] = this.value;
        });

        $datatable.on('focus','.datatable-filter', function () {
            if ($(this).hasClass("filter_init")) {
                $(this).removeClass("filter_init");
                $(this).val("");
            }
        });

        $datatable.on('blur','.datatable-filter', function () {
            if (this.value === "") {
                $(this).addClass("filter_init");
                $(this).val( asInitVals[$datatable.find('thead input').index(this)] );
            }
        });

        $datatable.on('keyup','.datatable-filter', function () {
            /* Filter on the column (the index) of this element, +1 to account for the row expander column */
            var columnIndexOfThisFilter = $datatable.find('thead input').index(this);
            $datatable.fnFilter(this.value, columnIndexOfThisFilter, false);
        });

        // we can't turn off filtering entirely on the table cause then our individual column filters won't work
        // so just hide the global (all-column) filter
        $("#" + datatableId + "_filter").css("display", "none");
    };

    historyTable.datatable = function(data, $datatable){
        var dataLengthToDisplay = 100;
        var datatableOptions = {
            aaData: data,
            aoColumns: [
                {
                    sTitle: '<b>Event Subscription</b>',
                    sClass: 'left',
                    sWidth: '200px',
                    mData: function(source){
                        var message = '<a class="view-history" href="#!" data-id="'+source.id+'" style="font-weight: bold">'+ source.subscription.name+'</a>';
                        if (source.trigger) {
                            message = message+ '<br>Trigger: '+source.trigger.label;
                        }
                        return message;
                    }
                },
                {
                    sTitle: '<b>Event Type</b>',
                    mData: function(source){
                        return (source.trigger) ? source.trigger['event-name'] : 'Unknown';
                    },
                    sWidth: '120px'
                },
                {
                    sTitle: '<b>Run As User</b>',
                    mData: function(source){
                        return source.user
                    },
                    sWidth: '120px'
                },
                {
                    sTitle: '<b>Project</b>',
                    mData: function(source){
                        return source.project
                    },
                    sWidth: '150px'
                },
                {
                    sTitle: '<b>Date</b>',
                    mData: function(source){
                        var timestamp = 0, dateString;
                        if (source.status.length > 0){
                            timestamp = source.status[0]['timestamp'].replace(/-/g,'/'); // include date format hack for Safari
                            if (timestamp.indexOf('UTC') <0) timestamp = timestamp.trim()+' UTC';
                            timestamp = new Date(timestamp);
                            dateString = timestamp.toLocaleString();
                            // dateString = timestamp.toISOString().replace('T',' ').replace('Z',' ').split('.')[0];

                        } else {
                            dateString = 'N/A';
                        }
                        return dateString
                    },
                    sWidth: '150px'
                }
            ],
            iDisplayLength: dataLengthToDisplay,
            fnDrawCallback: function(){
                console.log('drawn');
                if (data.length < dataLengthToDisplay) {
                    $(document).find('.dataTables_paginate').addClass('hidden');
                }
            }
        };

        $datatable.dataTable(datatableOptions);

        addColumnFilters($datatable,datatableOptions.aoColumns);
    };

    historyTable.table = function(data){

        var $dataRows = [];

        return {
            kind: 'table.dataTable',
            name: 'event-subscription-history',
            id: 'event-subscription-history',
            data: data,
            table: {
                classes: 'highlight hidden',
                on: [
                    ['click', 'a.view-history', viewHistoryDialog ]
                ]
            },
            trs: function(tr,data){
                tr.id = data.id;
                addDataAttrs(tr, {filter: '0' });
            },
            sortable: 'SUBSCRIPTION,EVENT,user,PROJECT,DATE',
            filter: 'SUBSCRIPTION,EVENT,user,PROJECT',
            items: {
                // by convention, name 'custom' columns with ALL CAPS
                // 'custom' columns do not correspond directly with
                // a data item
                SUBSCRIPTION: {
                    label: 'Event Subscription',
                    th: { className: 'left' },
                    td: { className: 'left' },
                    apply: function(){
                        var message = [ spawn('a.view-history',{ href: '#!', data: { id: this.id }, style: { 'font-weight': 'bold' }}, this.subscription.name) ];
                        if (this.trigger) {
                            message.push( spawn('span', { style: { display: 'block' }}, 'Trigger: '+this.trigger.label) )
                        }
                        return spawn('!', message);
                    }
                },
                EVENT: {
                    label: 'Event Type',
                    th: { className: 'left' },
                    td: { className: 'left' },
                    apply: function(){
                        return (this.trigger) ? this.trigger['event-name'] : 'Unknown';
                    }
                },
                user: {
                    label: 'Run As User',
                    th: { className: 'left' },
                    td: { className: 'left' },
                    apply: function(){
                        return this.user;
                    }
                },
                PROJECT: {
                    label: 'Project',
                    th: { className: 'left' },
                    td: { className: 'left' },
                    apply: function(){
                        return this.project;
                    }
                },
                DATE: {
                    label: 'Date',
                    th: { className: 'left' },
                    td: { className: 'left mono'},
                    filter: function(table){
                        var MIN = 60*1000;
                        var HOUR = MIN*60;
                        var X8HRS = HOUR*8;
                        var X24HRS = HOUR*24;
                        var X7DAYS = X24HRS*7;
                        var X30DAYS = X24HRS*30;
                        return spawn('div.center', [XNAT.ui.select.menu({
                            value: 0,
                            options: {
                                all: {
                                    label: 'All',
                                    value: 0,
                                    selected: true
                                },
                                lastHour: {
                                    label: 'Last Hour',
                                    value: HOUR
                                },
                                last8hours: {
                                    label: 'Last 8 Hrs',
                                    value: X8HRS
                                },
                                last24hours: {
                                    label: 'Last 24 Hrs',
                                    value: X24HRS
                                },
                                lastWeek: {
                                    label: 'Last Week',
                                    value: X7DAYS
                                },
                                last30days: {
                                    label: 'Last 30 days',
                                    value: X30DAYS
                                }
                            },
                            element: {
                                id: 'filter-select-container-timestamp',
                                on: {
                                    change: function(){
                                        var FILTERCLASS = 'filter-timestamp';
                                        var selectedValue = parseInt(this.value, 10);
                                        var currentTime = Date.now();
                                        $dataRows = $dataRows.length ? $dataRows : $$(table).find('tbody').find('tr');
                                        if (selectedValue === 0) {
                                            $dataRows.removeClass(FILTERCLASS);
                                        }
                                        else {
                                            $dataRows.addClass(FILTERCLASS).filter(function(){
                                                var timestamp = this.querySelector('input.subscription-timestamp');
                                                var subscriptionLaunch = +(timestamp.value);
                                                return selectedValue === subscriptionLaunch-1 || selectedValue > (currentTime - subscriptionLaunch);
                                            }).removeClass(FILTERCLASS);
                                        }
                                    }
                                }
                            }
                        }).element])
                    },
                    apply: function(){
                        var timestamp = 0, dateString;
                        if (this.status.length > 0){
                            timestamp = this.status[0]['timestamp'].replace(/-/g,'/'); // include date format hack for Safari
                            timestamp = new Date(timestamp);
                            dateString = timestamp.toISOString().replace('T',' ').replace('Z',' ').split('.')[0];

                        } else {
                            dateString = 'N/A';
                        }
                        return spawn('!',[
                            spawn('span', dateString ),
                            spawn('input.hidden.subscription-timestamp.filtering|type=hidden', { value: timestamp } )
                        ])
                    }
                }
            }

        }
    };

    historyTable.viewHistory = function(id){
        if (XNAT.admin.eventServicePanel.historyData[id]) {
            var historyEntry = XNAT.admin.eventServicePanel.historyData[id];
            var historyDialogButtons = [
                {
                    label: 'OK',
                    isDefault: true,
                    close: true
                }
            ];

            // build nice-looking history entry table
            var pheTable = XNAT.table({
                className: 'xnat-table compact',
                style: {
                    width: '100%',
                    marginTop: '15px',
                    marginBottom: '15px'
                }
            });

            // add table header row
            pheTable.tr()
                .th({ addClass: 'left', html: '<b>Key</b>' })
                .th({ addClass: 'left', html: '<b>Value</b>' });

            for (var key in historyEntry){
                var val = historyEntry[key], formattedVal = '';
                if (Array.isArray(val)) {
                    var items = [];
                    val.forEach(function(item){
                        if (typeof item === 'object') item = JSON.stringify(item);
                        items.push(spawn('li',[ spawn('code',item) ]));
                    });
                    formattedVal = spawn('ul',{ style: { 'list-style-type': 'none', 'padding-left': '0' }}, items);
                } else if (typeof val === 'object' ) {
                    formattedVal = spawn('code', JSON.stringify(val));
                } else if (!val) {
                    formattedVal = spawn('code','false');
                } else {
                    formattedVal = spawn('code',val);
                }

                pheTable.tr()
                    .td('<b>'+key+'</b>')
                    .td([ spawn('div',{ style: { 'word-break': 'break-all','max-width':'600px' }}, formattedVal) ]);
            }

            // display history
            XNAT.ui.dialog.open({
                title: historyEntry['wrapper-name'],
                width: 800,
                scroll: true,
                content: pheTable.table,
                buttons: historyDialogButtons
            });
        } else {
            console.log(id);
            XNAT.ui.dialog.open({
                content: 'Sorry, could not display this history item.',
                buttons: [
                    {
                        label: 'OK',
                        isDefault: true,
                        close: true
                    }
                ]
            });
        }
    };

    $(document).off('click','a.v0ew-history').on('click','a.view-history',function(e){
        e.preventDefault();
        var historyEntry = $(this).data('id');
        if (historyEntry) historyTable.viewHistory(historyEntry);
    });

    historyTable.init = historyTable.refresh = function(container){
        var $container = $$(container || '#history-table-container'), _historyTable;

        historyTable.getHistory().done(function(data){
            if (data.length){

                $container.empty().append(spawn('h3', { style: { 'margin-bottom': '1em' }}, data.length + ' Event Subscriptions Delivered On This Site'))
                $container.append('<table class="xnat-table data-table compact" id="event-history-table" style="width: 100%"></table>');
                var $datatable = $(document).find('#event-history-table');
                historyTable.datatable(data,$datatable);

                // _historyTable = XNAT.spawner.spawn({
                //     historyTable: historyTable.table(data)
                // });
                // _historyTable.done(function(){
                //     $container.empty().append(
                //         spawn('h3', { style: { 'margin-bottom': '1em' }}, data.length + ' Event Subscriptions Delivered On This Site')
                //     );
                //     this.render($container, 20);
                // });
            } else {
                $container.empty().append(spawn('p','No event history to display'));
            }
        })
    };

    /* ------------------------- *
     * Initialize tabs & Display *
     * ------------------------- */

   eventServicePanel.populateDisplay = function(rootDiv) {
        var $container = $(rootDiv || '#event-service-admin-tabs');
        $container.empty();

        var subscriptionTab =  {
            kind: 'tab',
            label: 'Event Subscriptions',
            group: 'General',
            active: true,
            contents: {
                subscriptionPanel: {
                    kind: 'panel',
                    label: 'Event Subscriptions',
                    contents: {
                        subscriptionFilterBar: {
                            tag: 'div#subscriptionFilters',
                            contents: {
                                addNewSubscription: {
                                    tag: 'button#create-new-subscription.pull-right.btn1',
                                    contents: 'Add New Event Subscription'
                                },
                                clearfix: {
                                    tag: 'div.clear.clearfix',
                                    contents: '<br>'
                                }
                            }
                        },
                        subscriptionTableContainer: {
                            tag: 'div#subscriptionTableContainer'
                        }
                    }
                }
            }
        };
        var historyTab = {
            kind: 'tab',
            label: 'Event Service History',
            group: 'General',
            contents: {
                eventHistoryPanel: {
                    kind: 'panel',
                    label: 'Event Subscription History',
                    footer: false,
                    contents: {
                        eventHistoryContainer: {
                            tag: 'div#history-table-container'
                        }
                    }
                }
            }
        };
        var eventTabSet = {
            kind: 'tabs',
            name: 'eventSettings',
            label: 'Event Service Administration',
            contents: {
                subscriptionTab: subscriptionTab,
                historyTab: historyTab
            }
        };

        eventServicePanel.tabSet = XNAT.spawner.spawn({ eventSettings: eventTabSet });
        eventServicePanel.tabSet.render($container);

        eventServicePanel.showSubscriptionList();

        XNAT.ui.tab.activate('subscription-tab');
    };

   eventServicePanel.showSubscriptionList = eventServicePanel.refreshSubscriptionList = function(container){
       var $container = $(container || '#subscriptionTableContainer');
       $container
           .empty()
           .append( eventServicePanel.subscriptionTable() );
   };

    eventServicePanel.init = function(){

        // Prerequisite: Get known events
        // translate events array into an object driven by the event ID

        eventServicePanel.getEvents().done(function(events){
            events.forEach(function(event){
                eventServicePanel.events[event.type] = event;
            });

            eventServicePanel.getActions().done(function(actions){
                actions.forEach(function(action){
                    eventServicePanel.actions[action['action-key']] = action;
                });

                // Populate event subscription table
                eventServicePanel.populateDisplay();

                // initialize history table
                eventServicePanel.historyTable.init();
            });

        });

        // initialize arrays of values that we'll need later
        eventServicePanel.getProjects().done(function(data){
            eventServicePanel.projects = data.ResultSet.Result;
        });
    };

}));
