/*
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2021, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 *
 * @author: Mohana Ramaratnam (mohana@radiologics.com)
 * @since: 07-03-2021
 */

console.log('IN formJsonConfig.js');

var XNAT = getObject(XNAT || {});

(function(factory) {
    if (typeof define === 'function' && define.amd) {
        define(factory);
    } else if (typeof exports === 'object') {
        module.exports = factory();
    } else {
        return factory();
    }
}(function() {

    var xnatFormManager, undefined, undef,
        restUrl = XNAT.url.restUrl;

    var projectDataTypeSingularName = XNAT.app.displayNames.singular.project;
    var addNewBuilderObj = {};

    var onAddNewBuilderChange = function (build) {
        try {
            let modalId = 'addNewModal';
            let button_id = modalId + '-save-button';
            let saveBtn = document.getElementById(button_id);
            saveBtn.removeAttribute('disabled');
            saveBtn.classList.remove("disabled");
        }catch(err) {console.log(err);}
        addNewBuilderObj.builderSchema = build;
    };


    XNAT.customFormManager =
        getObject(XNAT.customFormManager || {});


    XNAT.customFormManager.protocolManager = getObject(XNAT.customFormManager.protocolManager || {});
    XNAT.customFormManager.datatypeManager = getObject(XNAT.customFormManager.datatypeManager || {});

    XNAT.customFormManager.xnatFormManager = xnatFormManager =
        getObject(XNAT.customFormManager.xnatFormManager || {});

    xnatFormManager.sitedefinitions = [];

    xnatFormManager.siteHasProtocolsPluginDeployed = false;
    xnatFormManager.isProjectOwnerFormCreationEnabled = false;
    xnatFormManager.isCustomVariableMigrationEnabled = false;

    const PRIMARY_KEY_FIELDNAME = "idCustomVariableFormAppliesTo";


    function spacer(width) {
        return spawn('i.spacer', {
            style: {
                display: 'inline-block',
                width: width + 'px'
            }
        })
    }

    xnatFormManager.customFormUrl = function(append, paramsForMe) {
        var params = paramsForMe || {};
        return XNAT.url.restUrl('xapi/customforms/' + append, params, false, true);
    }

    function errorHandler(e, title, closeAll) {
        console.log(e);
        title = (title) ? 'Error Found: ' + title : 'Error';
        closeAll = (closeAll === undefined) ? true : closeAll;
        var errormsg = (e.statusText) ? '<p><strong>Error ' + e.status + ': ' + e.statusText + '</strong></p><p>' + e.responseText + '</p>' : e;
        XNAT.dialog.open({
            width: 450,
            title: title,
            content: errormsg,
            buttons: [{
                label: 'OK',
                isDefault: true,
                close: true,
                action: function () {
                    if (closeAll) {
                        xmodal.closeAll();
                    }
                }
            }]
        });
    }

    function setupBuilder(builder) {
        builder.on('change',onAddNewBuilderChange);
        Formio.Builders.addBuilder("addNew",builder);
        let formComponentDivs = document.getElementsByClassName('formcomponents');
        let formAreaDivs = document.getElementsByClassName('formarea');
        try {
            let formComponentDiv = formComponentDivs[0];
            let formAreaDiv = formAreaDivs[0];
            formComponentDiv.setAttribute('style','height:65vh; overflow-y:scroll');
            formAreaDiv.setAttribute('style','height:60vh; overflow-y:scroll');
        }catch(err){console.log("Could not add scroll bar");}
    }


    // Is Visit and Protocols Plugin Installed?
    xnatFormManager.getDeploymentEnvironment = function (callback) {
        callback = isFunction(callback) ? callback : function () {
        };
        return XNAT.xhr.get({
            url: restUrl('xapi/customforms/env'),
            dataType: 'json',
            async: false,
            success: function (data) {
                xnatFormManager.siteHasProtocolsPluginDeployed = data.siteHasProtocolsPluginDeployed;
                let features = data.features || {};
                xnatFormManager.isProjectOwnerFormCreationEnabled = features.isProjectOwnerFormCreationEnabled;
                xnatFormManager.isCustomVariableMigrationEnabled = features.isCustomVariableMigrationEnabled;
                callback.apply(this, arguments);
            }
        });
    };

    // Are we in a project context?
    xnatFormManager.isItAProjectContext = function (callback) {
        callback = isFunction(callback) ? callback : function () {
        };
        if (XNAT.data.context.projectID.length > 0) {
            return true;
        } else {
            return false;
        }
    };

    function defaultFormBuilderSchema(form) {
        return {
            display: "form",
            title: form.data.formTitle,
            components: [],
            settings: {}
        };
    }

    function initBuilder(form) {
        let formBuilderElement = document.getElementById("form-builder");
        let builderConfig = xnatFormManager.getBuilderConfiguration();
        let builderSchema = !jQuery.isEmptyObject(addNewBuilderObj) && addNewBuilderObj.hasOwnProperty('builderSchema') ?
            addNewBuilderObj.builderSchema :
            defaultFormBuilderSchema(form);
        Formio.builder(formBuilderElement, builderSchema, {
            noDefaultSubmitButton: true,
            builder: builderConfig
        }).then((builder) => {
            setupBuilder(builder);
        });
    }


    function saveConfiguration() {
        let builder = Formio.Builders.getBuilder("addNew");
        let builderSchema = builder.schema;
        let submission = {
            'submission': addNewBuilderObj.submission,
            'builder': builderSchema
        };

        var url = restUrl('xapi/customforms/save', {}, false, true);

        XNAT.xhr.put({
            url: url,
            contentType: 'application/json',
            data: JSON.stringify(submission),
            success: function () {
                xmodal.closeAll();
                XNAT.ui.banner.top(2000, 'Configuration saved.', 'success');
                xnatFormManager.refreshTable();
            },
            fail: function (e) {
                errorHandler(e, 'Could Not save the form', false);
            }
        });

    }

    function getPK(item) {
        return item['appliesToList'][0][PRIMARY_KEY_FIELDNAME];
    }


    function getSubmissionObjectForRow(configItem) {
        let dbRowId = getPK(configItem);
        let path = configItem.path;
        let zIndex = configItem.formZIndex;
        let submissionJson = {};
        let submissionObj = {};
        let submissionDataObj = {};
        if (dbRowId == undef) {
            submissionDataObj[PRIMARY_KEY_FIELDNAME] = '-1_-1';
        } else {
            submissionDataObj[PRIMARY_KEY_FIELDNAME] = dbRowId;
        }
        submissionDataObj['formType'] = 'form';
        submissionDataObj['formTitle'] = configItem['contents']['title'];
        let dataXsiType = extractParts(path, 1);
        let dataSingular = XNAT.customFormManager.datatypeManager.getDatatypeByXsiType(dataXsiType).label;
        let xnatDataTypeObj = {};
        xnatDataTypeObj['label'] = dataSingular;
        xnatDataTypeObj['value'] = dataXsiType;
        submissionDataObj['xnatDatatype'] = xnatDataTypeObj;
        submissionDataObj['xnatProject'] = [];
        submissionDataObj['xnatProtocol'] = [];
        submissionDataObj['xnatVisit'] = [];
        submissionDataObj['xnatSubtype'] = [];

        let projectList = getProjects(configItem);
        submissionDataObj.zIndex = zIndex;
        let rowProtocol = extractParts(configItem['path'], 3);
        if (configItem.scope === "Site") {
            // A site wide configuration
            submissionDataObj['isThisASiteWideConfiguration'] = 'yes';
        } else {
            submissionDataObj['isThisASiteWideConfiguration'] = 'no';
            projectList.forEach(project => {
                let xnatProject = {};
                if (rowProtocol === '--') {
                    xnatProject['label'] = project;
                    xnatProject['value'] = project;
                } else {
                    xnatProject['label'] = project + "[" + rowProtocol + "]";
                    xnatProject['value'] = rowProtocol + ":" + project;
                }
                submissionDataObj['xnatProject'].push(xnatProject);
            });
        }
        if (rowProtocol != '--') {
            let xnatProtocol = {};
            xnatProtocol['label'] = rowProtocol;
            xnatProtocol['value'] = rowProtocol;
            submissionDataObj['xnatProtocol'].push(xnatProtocol);
            let visit = extractParts(configItem['path'], 5);
            if (visit != '--') {
                let xnatVisit = {};
                xnatVisit['label'] = rowProtocol + ":" + visit;
                xnatVisit['value'] = rowProtocol + ":" + visit;
                submissionDataObj['xnatVisit'].push(xnatVisit);
                let subType = extractParts(configItem['path'], 7);
                if (subType != '--') {
                    let xnatSubType = {};
                    xnatSubType['label'] = rowProtocol + ":" + visit + ":" + subType;
                    xnatSubType['value'] = rowProtocol + ":" + visit + ":" + subType;
                    submissionDataObj['xnatSubtype'].push(xnatSubType);
                }
            }
        }
        submissionObj['data'] = submissionDataObj;
        submissionObj['state'] = 'submitted';
        submissionJson['submission'] = submissionObj;
        return submissionJson;
    }


    // prepare to display the wizard for form creation
    xnatFormManager.getWizard =  function (callback) {
        callback = isFunction(callback) ? callback : function () {};
        let url = XNAT.url.scriptUrl('/xnat/app/formJson/formManagerWizard.json');
        if (xnatFormManager.siteHasProtocolsPluginDeployed) {
            url = XNAT.url.scriptUrl('/xnat/app/formJson/formManagerWizard_protocol.json');
        }
        let formWizardJson = undefined;
        XNAT.xhr.get({
            url: url,
            dataType: 'json',
            async: false,
            success: function (data) {
                formWizardJson = data;
            }
        });
        Formio.createForm(document.getElementById('formio'), formWizardJson, {
            breadcrumbSettings: {clickable:false},
            buttonSettings: {
                showCancel: false,
                showSubmit: false
            }
        }).then(function (wizard) {
            addNewBuilderObj.submission = wizard.submission;
            // Prevent the submission from going to the form.io server.
            wizard.nosubmit = true;
            wizard.on('nextPage', function (page) {
                if (xnatFormManager.siteHasProtocolsPluginDeployed == true) {
                    if (page.page === 3) {
                        if (wizard.data.isThisASiteWideConfiguration === 'no' && (wizard.data.xnatProject == undef || wizard.data.xnatProject.length == 0)) {
                            XNAT.dialog.message('ERROR ', 'Please select atleast one project.');
                            wizard.prevPage();
                        }
                        initBuilder(wizard);
                    }
                } else {
                    if (page.page === 2) {
                        if (wizard.data.isThisASiteWideConfiguration === 'no' && (wizard.data.xnatProject == undef || wizard.data.xnatProject.length == 0)) {
                            XNAT.dialog.message('ERROR ', 'Please select atleast one project.');
                            wizard.prevPage();
                        }
                        initBuilder(wizard);
                    }
                }
            });
        });
    };


    // get the list of Site wide Configs
    xnatFormManager.getCustomFormConfigs = xnatFormManager.getAllCustomFormConfigs = function (callback) {
        callback = isFunction(callback) ? callback : function () {
        };
        return XNAT.xhr.get({
            url: restUrl('xapi/customforms'),
            dataType: 'json',
            async: false,
            success: function (data) {
                xnatFormManager.sitedefinitions = [];
                data.forEach(function (item) {
                    xnatFormManager.sitedefinitions.push(item);
                });
                xnatFormManager.sitedefinitions.sort(function (a, b) {
                    return (a.path > b.path) ? 1 : -1;
                });
                callback.apply(this, arguments);
            },
            fail: function (e) {
                errorHandler(e, 'Could not retrieve forms');
            }

        });
    };


    xnatFormManager.getBuilderConfiguration = function () {
        return XNAT.customFormManager.builderConfigManager.getBuilderConfig();
    }

    xnatFormManager.builderDialog = function (configDefinition) {
            let configDefinitionObj = JSON.parse(configDefinition['contents']);
            let builderConfig = xnatFormManager.getBuilderConfiguration();
                const componentNames = Formio.Components._components;
                let fieldsToHide = {};
                for (const component in componentNames) {
                  fieldsToHide = {
                    ...fieldsToHide,
                    [component]: [
                      {
                        key: "display",
                        ignore: false,
                        components: [
                          {
                            key: "labelPosition",
                            ignore: true,
                          },
                          {
                            key: "optionsLabelPosition",
                            ignore: true,
                          },
                          {
                            key: "displayMask",
                            ignore: true,
                          },
                          {
                            key: "hideLabel",
                            ignore: true,
                          },
                          {
                            key: "tableView",
                            ignore: true,
                          },
                          {
                            key: "editor",
                            ignore: true,
                          },
                          {
                            key: "uniqueOptions",
                            ignore: true,
                          },
                          {
                            key: "modalEdit",
                            ignore: true,
                          },
                          {
                            key: "tableView",
                            ignore: true,
                          },
                          {
                            key: "inputType",
                            ignore: true,
                          },
                          {
                            key: "tooltip",
                            ignore: true,
                          },
                          {
                            key: "prefix",
                            ignore: true,
                          },
                          {
                            key: "suffix",
                            ignore: true,
                          },
                          { key: "spellcheck", ignore: true },
                        ],
                      },
                      {
                        key: "data",
                        ignore: false,
                        components: [
                          {
                            key: "protected",
                            ignore: true,
                          },
                          {
                            key: "encrypted",
                            ignore: true,
                          },
                          {
                            key: "persistent",
                            ignore: true,
                          },
                          {
                            key: "dbIndex",
                            ignore: true,
                          },
                          {
                            key: "calculateServer",
                            ignore: true,
                          },
                          {
                            key: "allowCalculateOverride",
                            ignore: true,
                          },
                          {
                            key: "inputFormat",
                            ignore: true,
                          },
                          {
                            key: "customDefaultValuePanel",
                            ignore: false,
                            components: [
                              {
                                key: "customDefaultValue-js",
                                ignore: true,
                              },
                            ],
                          },
                          {
                            key: "calculateValuePanel",
                            ignore: false,
                            components: [
                              {
                                key: "calculateValue-js",
                                ignore: true,
                              },
                            ],
                          },
                        ],
                      },
                      {
                        key: "validation",
                        ignore: false,
                        components: [
                          { key: "unique", ignore: true },
                          { key: "validate.pattern", ignore: true },
                          { key: "kickbox", ignore: true },
                          { key: "custom-validation-js", ignore: true },
                          {
                            key: "validate.onlyAvailableItems",
                            ignore: true,
                          },
                        ],
                      },
                      {
                        key: "api",
                        ignore: false,
                        components: [
                          { key: "tags", ignore: true },
                          { key: "properties", ignore: true },
                        ],
                      },
                      {
                        key: "conditional",
                        ignore: false,
                        components: [
                          {
                            key: "customConditionalPanel",
                            ignore: false,
                            components: [
                              {
                                key: "customConditional-js",
                                ignore: true,
                              },
                            ],
                          },
                        ],
                      },
                      {
                        key: "logic",
                        ignore: true,
                      },
                      {
                        key: "layout",
                        ignore: true,
                      },
                    ],
                  };
                }
            Formio.builder(
              document.getElementById("formio-builder"),
              configDefinitionObj,
              {
                noDefaultSubmitButton: true,
                builder: builderConfig,
                editForm: fieldsToHide,
              }
            ).then((form) => {
              Formio.Builders.addBuilder("wysiwyg", form);
            });
    };




    xnatFormManager.submitJson = function(submissionJson) {
        let url = restUrl('xapi/customforms/save', {}, false, true);

        XNAT.xhr.put({
            url: url,
            contentType: 'application/json',
            data: JSON.stringify(submissionJson),
            success: function () {
                xmodal.closeAll();
                XNAT.ui.banner.top(2000, 'Form JSON definition updated.', 'success');
                xnatFormManager.refreshTable();
            },
            fail: function (e) {
                errorHandler(e, 'Could Not save the form', false);
            }
        });

    }


    xnatFormManager.dialog = function (configDefinition, newCommand) {
        let _source, _editor;
        if (!newCommand) {
            let path = configDefinition.path;
            let configDefinitionObj = JSON.parse(configDefinition['contents']);
            let label = configDefinitionObj.title;
            label = label || {};

            let dialogButtons = {
                update: {
                    label: 'Save',
                    isDefault: true,
                    close: false,
                    action: function (obj) {
                        let editorContent = _editor.getValue().code;
                        let submissionJson = getSubmissionObjectForRow(configDefinition);
                        try {
                            submissionJson['builder'] = JSON.parse(editorContent);
                            xnatFormManager.submitJson(submissionJson);
                            obj.close();
                        } catch (error) {
                            console.error(error);
                            XNAT.dialog.confirm({
                                title: 'Invalid JSON',
                                content: 'Please fix the errors in the JSON.',
                                okAction: function(){
                                }
                            });
                        }

                    }
                },
                cancel: {
                    label: 'Cancel',
                    close: false,
                    action: function(obj) {
                        let $thisModal = obj.$modal;
                        xmodal.confirm({
                            content: 'Are you sure you want to abandon?',
                            okAction: function(){
                                // close 'parent' modal
                                xmodal.close($thisModal);
                            }
                        });
                    }
                }
            };
            _source = spawn('textarea', configDefinition.contents);

            _editor = XNAT.app.codeEditor.init(_source, {
                language: 'json'
            });

            _editor.openEditor({
                title: 'Form JSON Definition For ' + label,
                classes: 'plugin-json',
                buttons: dialogButtons,
                height: 680,
                closeBtn: false,
                afterShow: function (dialog, obj) {
                    obj.aceEditor.setReadOnly(false);
                    dialog.$modal.find('.body .inner').prepend(
                        spawn('div', [
                            spawn('p', 'Path: ' + path),
                        ])
                    );
                }
            });

        }
    };


    xnatFormManager.disable = function (configDefinition, title, formElement) {
        let appliesTo = configDefinition['appliesToList'];
        xmodal.open({
            title: 'Disable?',
            content: 'Are you sure you want to disable the form?',
            width: 200,
            height: 200,
            overflow: 'auto',
            buttons: {
                ok: {
                    label: 'Ok',
                    isDefault: true,
                    action: function () {
                        let url = xnatFormManager.customFormUrl('disable');
                        XNAT.xhr.post({
                            url: url,
                            contentType: 'application/json',
                            data: JSON.stringify(appliesTo),
                            success: function () {
                                xmodal.closeAll();
                                XNAT.ui.banner.top(2000, 'Form disabled.', 'success');
                                xnatFormManager.refreshTable();
                            },
                            fail: function (e) {
                                formElement.checked = !formElement.checked;
                                errorHandler(e, 'Could not disable form for ' + title);
                            }
                        });
                    }
                },
                close: {
                    label: 'Close',
                    action: function() {
                        formElement.checked = !formElement.checked;
                    }
                }
            }
        });

    }

    xnatFormManager.deleteForm = function (configDefinition, title) {
        let appliesTo = configDefinition['appliesToList'];
        xmodal.open({
            title: 'Delete form?',
            content: 'Are you sure you want to delete the form? <br><br><p>In case data has been acquired using the form, form will be disabled. This allows access to data in the future.</p>',
            width: 300,
            height: 300,
            overflow: 'auto',
            buttons: {
                ok: {
                    label: 'Proceed',
                    isDefault: true,
                    action: function () {
                        let url = xnatFormManager.customFormUrl('');
                        XNAT.xhr.delete({
                            url: url,
                            contentType: 'application/json',
                            data: JSON.stringify(appliesTo),
                            success: function (resp) {
                                xmodal.closeAll();
                                const regex = /\: disabled$/;
                                const found = resp.match(regex);
                                if (found != null) {
                                    xnatFormManager.warnUserDataExists();
                                } else {
                                    XNAT.ui.banner.top(4000, resp, 'success');
                                }
                                xnatFormManager.refreshTable();
                            },
                            fail: function (e) {
                                errorHandler(e, 'Could not delete form ' + title);
                            }
                        });
                    }
                },
                close: {
                    label: 'Cancel'
                }
            }
        });

    }

    xnatFormManager.warnUserDataExists = function () {
        XNAT.dialog.open({
            width: 450,
            title: "Form deletion not possible",
            content: "Form has been disabled as data has been acquired using the form.",
            buttons: [{
                label: 'OK',
                isDefault: true,
                close: true,
                action: function () {
                    xmodal.closeAll();
                }
            }]
        });
    }

    xnatFormManager.warnUserDataLossOnClosing = function () {
        XNAT.dialog.open({
            width: 450,
            title: "Are you sure you want to abandon form creation?",
            content: "You you sure you want to abandon form creation? All data will be lost.",
            buttons: [{
                label: 'OK',
                isDefault: true,
                close: true
            }]
        });
    }

    xnatFormManager.enable = function (configDefinition, title, formElement) {
        let appliesTo = configDefinition['appliesToList'];
        xmodal.open({
            title: 'Enable?',
            content: 'Are you sure you want to enable the form?',
            width: 200,
            height: 200,
            overflow: 'auto',
            buttons: {
                ok: {
                    label: 'Ok',
                    isDefault: true,
                    action: function () {
                        let url = xnatFormManager.customFormUrl('enable');
                        XNAT.xhr.post({
                            url: url,
                            contentType: 'application/json',
                            data: JSON.stringify(appliesTo),
                            success: function () {
                                xmodal.closeAll();
                                XNAT.ui.banner.top(2000, 'Configuration enabled.', 'success');
                                xnatFormManager.refreshTable();
                            },
                            fail: function (e) {
                                formElement.checked = !formElement.checked;
                                errorHandler(e, 'Could not enable configuration for ' + title);
                            }
                        });
                    }
                },
                close: {
                    label: 'Close',
                    action: function() {
                        formElement.checked = !formElement.checked;
                    }
                }
            }
        });

    }

    xnatFormManager.modifyDisplayOrder = function (configDefinition) {
        let formOrder = configDefinition['formZIndex'];
        let itemObj = JSON.parse(configDefinition['contents']);
        const title = itemObj['title'] || '';
        const dateCreated = new Date(configDefinition['dateCreated']);
        const formId = configDefinition['formId'];
        var info_button = '<div class="info">Relative form order is a preference set via integer values, where lower numbers reflect higher positions. If multiple forms have the same value, creation date is used as a tie breaker with more recently created forms shown first.</div>';
        xmodal.open({
            title: 'Change Form Order for ' + title,
            content: info_button + '<br><br>Current Form Order: ' + formOrder + '<br><br> Creation Date: ' + dateCreated + '<br><br> New Form Order: <input type="number" step="1"  id="formOrderTxt" value="'+ formOrder + '">',
            width: 500,
            height: 350,
            overflow: 'auto',
            buttons: {
                ok: {
                    label: 'Ok',
                    isDefault: true,
                    action: function () {
                        let desiredFormOrder = document.getElementById('formOrderTxt').value;
                        let containsDot = /\./.test(desiredFormOrder);
                        if (containsDot) {
                            XNAT.dialog.open({
                                width: 450,
                                title: "Invalid Value for Form Order",
                                content: "Form Order must be an integer value",
                                buttons: [{
                                    label: 'OK',
                                    isDefault: true,
                                    close: true
                                }]
                            });
                            return;
                        }
                        let url = xnatFormManager.customFormUrl( 'formId/' + formId + '?zIndex=' + desiredFormOrder );
                        XNAT.xhr.post({
                            url: url,
                            contentType: 'application/json',
                            success: function () {
                                xmodal.closeAll();
                                XNAT.ui.banner.top(2000, 'Form Order updated.', 'success');
                                xnatFormManager.refreshTable();
                            },
                            fail: function (e) {
                                errorHandler(e, 'Could not update Form Order ' + title);
                            }
                        });
                    }
                },
                close: {
                    label: 'Close'
                }
            }
        });
    }

    function displayFormWizard() {
        addNewBuilderObj = {};
        xnatFormManager.getWizard();
    }

    function getOptedOutProjectsForTheForm(formId) {
        let definitions = xnatFormManager.sitedefinitions;
        let optedOutFromThisForm = [];
        for (let k = 0; k < definitions.length; k++) {
            let item = definitions[k];
            if (item['scope'] === 'Project' && item['formId'] === formId && item['appliesToList'][0]['status'] === 'optedout') {
                let projects = getProjects(item);
                for (let i=0; i< projects.length; i++) {
                    optedOutFromThisForm.push(projects[i]);
                }
            }
        }
        return optedOutFromThisForm;
    }

    function getProjects(itemObj, status) {
            let projects = [];
            itemObj['appliesToList'].forEach(function (item) {
                if (item['entityId'] === 'Site') {
                    return;
                } else if (status != null) {
                    if (item['status'] === status) {
                        projects.push(item['entityId']);
                    }
                } else {
                    projects.push(item['entityId']);
                }

            });
            return projects.sort();
        }

    function extractParts(path, partIndex) {
        let pathParts = path.split('/');
        if (partIndex > pathParts.length) {
            return '--';
        } else {
            return pathParts[partIndex];
        }
    }

    xnatFormManager.addNewBtn = function (container, callback) {
        return spawn('button.btn1.btn-sm', {
            style: {
                float: "right"
            },
            onclick: function (e) {
                e.preventDefault();
                xmodal.open({
                    id: 'addNewModal',
                    title: 'Custom Form Generation',
                    classes: 'xnat-bootstrap',
                    template: $('#addFormVariable'),
                    width: 1600,
                    height: 2400,
                    closeBtn: false,
                    scroll: false,
                    beforeShow: function (obj) {
                       displayFormWizard();
                    },
                    buttons: {
                        save: {
                            label: 'Save',
                            isDefault: true,
                            disabled: true,
                            action: function () {
                                saveConfiguration();
                            }
                        },
                        cancel: {
                            label: 'Cancel',
                            close: false,
                            action: function(obj) {
                                let $thisModal = obj.$modal;
                                xmodal.confirm({
                                    content: 'Are you sure you want to abandon?',
                                    okAction: function(){
                                        // close 'parent' modal
                                        xmodal.close($thisModal);
                                    }
                                });
                            }
                        }
                    }
                });
            }
        }, 'Add New');
    }


    function saveWYSIWYGContent(itemObj) {
        let wysiygBuilder = Formio.Builders.getBuilder("wysiwyg");
        let editorContent = wysiygBuilder.schema;

        let submissionJson = getSubmissionObjectForRow(itemObj);
        submissionJson['builder'] = editorContent;

        let url = restUrl('xapi/customforms/save', {}, false, true);

        XNAT.xhr.put({
            url: url,
            contentType: 'application/json',
            data: JSON.stringify(submissionJson),
            success: function () {
                xmodal.closeAll();
                XNAT.ui.banner.top(2000, 'Form  updated.', 'success');
                xnatFormManager.refreshTable();
            },
            fail: function (e) {
                errorHandler(e, 'Could Not save the form', false);
            }
        });

    }

    function editWindow(itemObj) {
        xmodal.open({
        title: 'Edit Custom Form',
        classes: 'xnat-bootstrap',
        template: $('#editForm'),
        width: 1600,
        height: 1600,
        scroll: false,
        closeBtn: false,
        beforeShow: function (obj) {
            var has_data = false;
            XNAT.xhr.get({
                url: xnatFormManager.customFormUrl( 'hasdata/' + itemObj['appliesToList'][0]['idCustomVariableFormAppliesTo']),
                dataType: 'json',
                async: false,
                success: function (data) {
                    has_data = data;
                }
            });
            if (has_data) {
                let $thisModal = obj.$modal;
                xmodal.confirm({
                    content: 'This form already has data associated with it. Use caution when editing this form as it may affect the functioning of the previously collected data.',
                    okAction: function(){
                    },
                    cancelAction: function() {
                       // close 'parent' modal
                       xmodal.close($thisModal);
                    }
                });
            }
            xnatFormManager.builderDialog(itemObj);
        },
        afterShow: function(o) {
          let formComponentDivs = document.getElementsByClassName('formcomponents');
          let formAreaDivs = document.getElementsByClassName('formarea');
          try {
              let formComponentDiv = formComponentDivs[0];
              let formAreaDiv = formAreaDivs[0];
              formComponentDiv.setAttribute('style','height:75vh; overflow-y:scroll');
              formAreaDiv.setAttribute('style','height:80vh; overflow-y:scroll');
          }catch(err){console.log("Could not add scroll bar");}
        },
        buttons: {
            update: {
                label: 'Save',
                isDefault: true,
                action: function () {
                    saveWYSIWYGContent(itemObj);
                }
            },
            editJson: {
                label: 'Edit JSON',
                close: true,
                action: function () {
                    xnatFormManager.dialog(itemObj, false);
                }
            },
            cancel: {
                label: 'Cancel',
                close: false,
                action: function(obj) {
                    let $thisModal = obj.$modal;
                    xmodal.confirm({
                        content: 'Are you sure you want to abandon?',
                        okAction: function(){
                            // close 'parent' modal
                            xmodal.close($thisModal);
                        }
                    });
                }
            }
        }
    });
    }

    function editLink(itemObj, text){
        return spawn('a.link|href=#!', {
            onclick: function(e){
                e.preventDefault();
                editWindow(itemObj)
            }
        }, [['b', text]]);
    }

    function editButton(itemObj) {
        return spawn('button.btn.btn-sm.edit', {
            onclick: function (e) {
                e.preventDefault();
                editWindow(itemObj)
            },
            title: "Edit the form definition"
        }, [ spawn('i.fa.fa-pencil') ]);
    }

    function deleteButton(itemObj, title) {
        let status = itemObj['status'];
        return spawn('button.btn.btn-sm.edit', {
            onclick: function (e) {
                e.preventDefault();
                if (itemObj) {
                    xnatFormManager.deleteForm(itemObj, title);
                }
            },
            title: "Delete the form"
        }, [ spawn('i.fa.fa-trash') ]);
    }

    function displayOrderButton(itemObj) {
        return spawn('button.btn.btn-sm.edit', {
            onclick: function (e) {
                e.preventDefault();
                if (itemObj) {
                    xnatFormManager.modifyDisplayOrder(itemObj);
                }
            },
            title: "Change the order of the form relative to others"
        }, [ spawn('i.fa.fa-exchange') ]);
    }

    function manageProjectsButton(itemObj, title) {
        return spawn('button.btn.btn-sm.edit', {
            onclick: function (e) {
                e.preventDefault();
                if (itemObj) {
                    let projects = getProjects(itemObj);
                    let rowId = getPK(itemObj);
                    let title = itemObj.title || '';
                    let isSiteWide = itemObj.scope === "Site";
                    XNAT.customFormManager.assignDialog.assignProject(itemObj, title, rowId, isSiteWide, projects);
                }
            },
            title: "Manage which projects are associated with this form"
        }, [ spawn('i.fa.fa-list') ]);
    }


    function spawnProjects(item) {
        var returnItems = []
        if (item.scope === "Site") {
            let projectsArray = getProjects(item, 'optedout');
            returnItems.push(spawn('p', 'All Projects'));
            if (projectsArray.length > 0) {
                returnItems.push(spacer(2));
                let title = " Opted Out of Form";
                returnItems.push(spawn('button.btn.btn-sm.edit', {
                     onclick: function (e) {
                         e.preventDefault();
                         XNAT.customFormManager.projectListModalManager.show(projectsArray, title);
                     }
                 }, projectsArray.length + " Projects Opted Out"));
            }
            return returnItems;
        }
        let projectsArray = getProjects(item);
        if (typeof projectsArray === "string") {
            return projectsArray;
        }

        if (projectsArray.length < 4) {
            return projectsArray.join(", ")
        } else {
            let title = item.title || '';

            returnItems.push(spawn('p', projectsArray.slice(0, 3).join(", ")));
            returnItems.push(spawn('button.btn.btn-sm.edit', {
                onclick: function (e) {
                    e.preventDefault();
                    XNAT.customFormManager.projectListModalManager.show(projectsArray, title);
                }
            }, 'View All'));
            return returnItems;
        }
    }

    function spawnStatusColumn(item) {

        let title = item.title || '';

        return(XNAT.ui.panel.input.switchbox({
            name: 'enable_form_'+item.formId,
            checked: item['appliesToList'][0]['status'] === "enabled",
            onclick: function() {
                var checkbox = this;
                enabled = checkbox.checked;
                if (enabled === true) {
                    xnatFormManager.enable(item, title, this);
                } else {
                    xnatFormManager.disable(item, title, this);
                }
            }
        }));

    }

    // table cell formatting
    function truncCell(val, truncClass) {
        let elClass = truncClass ? 'truncate ' + truncClass : 'truncate';
        return spawn('span', {
            className: elClass,
            title: val,
            html: val
        });
    }


    // create table for FormIO JSON Forms for site
    xnatFormManager.table = function (container, callback) {
        let tableData = [];
        let definitions = xnatFormManager.sitedefinitions;
        let DATA_FIELDS = 'title, datatype, status, formCreator, project';
        for (let k = 0; k < definitions.length; k++) {
            let item = definitions[k];
            let itemObj = JSON.parse(item['contents']);
            let title = itemObj.title || '';
            let tableDataRow = {};
            tableDataRow['title'] = title;
            tableDataRow['datatype'] = XNAT.customFormManager.datatypeManager.getDatatypeByXsiType(extractParts(item['path'], 1)).label;
            tableDataRow['project'] = item;
            if (xnatFormManager.siteHasProtocolsPluginDeployed) {
                tableDataRow['protocol'] = extractParts(item['path'], 3);
                tableDataRow['visit'] = extractParts(item['path'], 5);
                tableDataRow['subtype'] = extractParts(item['path'], 7);
            }
            tableDataRow['formCreator'] = xnatFormManager.prettyPrint(item['username']);
            tableDataRow['status'] = item;
            tableDataRow['actions'] = item;
            tableData.push(tableDataRow);
        }


    let columns = {
        title: {
            label: 'Title'
        },
        datatype: {
            label: 'Datatype',
            sortable: true
        },
        project: {
            label: projectDataTypeSingularName,
            sortable: true
        },
        formCreator: {
            label: 'Form Creator',
            sortable: true
        }
    };
    if (xnatFormManager.siteHasProtocolsPluginDeployed) {
        columns['protocol'] = {
            label: 'Protocol',
            sortable: true
        };
        columns['visit'] = {
            label: 'Visit',
            sortable: true
        };
        columns['subtype'] = {
            label: 'Subtype',
            sortable: true
        };
    }
    columns['status'] = {
        label: 'Status',
        sortable: true
    };
    columns['actions'] = {
        label: 'Actions'
    };


    let columnsInTable = {
        title: {
            label: 'Title',
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (title) {
                return editLink(this.actions, title);
            }
        },
        datatype: {
            label: 'Datatype',
            sortable: true,
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (datatype) {
                return truncCell.call(this, datatype, '');
            }
        },
        project: {
            label: projectDataTypeSingularName,
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (project) {
                return spawnProjects.call(this, project, '');
            }
        }
    };
    if (xnatFormManager.siteHasProtocolsPluginDeployed) {
        columnsInTable['protocol'] = {
            label: 'Protocol',
            sortable: true,
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (protocol) {
                return truncCell.call(this, protocol, '');
            }
        };
        columnsInTable['visit'] = {
            label: 'Visit',
            sortable: true,
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (visit) {
                return truncCell.call(this, visit, '');
            }
        };
        columnsInTable['subtype'] = {
            label: 'Subtype',
            sortable: true,
            td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (subtype) {
                return truncCell.call(this, subtype, '');
            }
        };

    }
        columnsInTable['formCreator']= {
            label: 'Form Creator',
                td: {
                style: {
                    verticalAlign: 'middle'
                }
            },
            apply: function (formCreator) {
                return truncCell.call(this, formCreator, '');
            }
        };
        columnsInTable['status']= {
            label: 'Status',
                td: {
                style: {
                    verticalAlign: 'middle',
                    textAlign: 'center'
                }
            },
            apply: function (status) {
                return spawnStatusColumn(status, '');
            }
        };
        columnsInTable['actions']= {
            label: 'Actions',
                td: {
                style: {
                    verticalAlign: 'middle',
                    width: '170px'
                }
            },
            apply: function (actions) {
                return xnatFormManager.getActionButtons(actions);
            }
        };



        let projectFormsTable = XNAT.table.dataTable(tableData, {
        container: container,
        header: true,
        sortable: DATA_FIELDS,
        filter: DATA_FIELDS,
        height: 'auto',
        overflowY: 'scroll',
        table: {
            className: 'project-formjson xnat-table selectable scrollable-table',
            style: {
                width: '100%',
                marginTop: '15px',
                marginBottom: '15px'
            }
        },
        columns: columnsInTable
    });

        xnatFormManager.$table = $(projectFormsTable.table);

        return projectFormsTable.table;
    };

    xnatFormManager.prettyPrint = function(statusStr) {
        return statusStr[0].toUpperCase() + statusStr.slice(1, statusStr.length);
    }

    xnatFormManager.getActionButtons = function(item) {
        let isProjectSpecific = item.scope === "Project";
        let isFormSharedBetweenProjects = item['doProjectsShareForm'];

        let title = item['title'] || '';
        let status = item['appliesToList'][0]['status'];
        let isEnabled = false;
        if (status === 'enabled') {
            isEnabled = true;
        }
        let hasData = false;
        if (item['hasData']) {
            hasData = true;
        }
        let actions = [];
        if (isEnabled) {
            actions = [editButton(item), spacer(4), displayOrderButton(item), spacer(4), manageProjectsButton(item, title)];
            if (!hasData) {
                actions.push(spacer(4));
                actions.push(deleteButton(item, title))
            }
        }else {
            if (!hasData) {
                actions = [deleteButton(item, title)];
            }
        }
        return actions;
    }

    xnatFormManager.init = function(container) {
        xnatFormManager.getDeploymentEnvironment();
        XNAT.customFormManager.datatypeManager.init();
        if (xnatFormManager.siteHasProtocolsPluginDeployed) {
            XNAT.customFormManager.protocolManager.init();
        }
        xnatFormManager.getAllCustomFormConfigs();

        let $manager = $$(container || 'div#form-json-container');

        xnatFormManager.$container = $manager;

        let headerTitle = document.getElementById('headerTitle');
        headerTitle.append(xnatFormManager.addNewBtn());

        $manager.append(xnatFormManager.table());

        return {
            element: $manager[0],
            spawned: $manager[0],
            get: function() {
                return $manager[0]
            }
        };


    };

    xnatFormManager.refresh = xnatFormManager.refreshTable = function() {
       xnatFormManager.getAllCustomFormConfigs();
        if (typeof xnatFormManager.$table != 'undefined') {
            xnatFormManager.$table.remove();
        }
        let $manager = $('div#form-json-container');
        $manager.append(xnatFormManager.table());
    };


    xnatFormManager.init();

    //We need the following code snippet to force the xnat-bootstrap onto the
    //dynamically added div elements of the form builder
    var observer = new MutationObserver(function(mutations) {
        mutations.forEach(function(mutation) {
            for(let node of mutation.addedNodes) {
                if(!(node instanceof HTMLElement)) continue;
                // check the inserted element for being a code snippets
                // only add the class if the generated element is part of the form builder
                if(node.matches('div') && node.outerHTML.includes('formio')) {
                    node.classList.add("xnat-bootstrap");
                }
            }
        });
    });

    observer.observe(document.getElementById('page_body'), { childList: true });


    return XNAT.customFormManager.xnatFormManager = xnatFormManager;

}));