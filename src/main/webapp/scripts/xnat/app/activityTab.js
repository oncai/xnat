/*
 * web: activityTab.js
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

/*!
 * Monitor async processing
 */

var XNAT = getObject(XNAT);

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
    let activityTab, cookieTag;

    XNAT.app = getObject(XNAT.app || {});

    XNAT.app.activityTab = activityTab =
        getObject(XNAT.app.activityTab || {});

    XNAT.app.activityTab.cookieTag = cookieTag = 'activities';

    activityTab.pollers = [];

    activityTab.init = function() {
        var activities = JSON.parse(XNAT.cookie.get(cookieTag) || "{}");
        $.each(activities, function(key, item) {
            activityTab.startPoll(item, key);
        });
    };

    activityTab.start = function(title, statusListenerId, callbackPath = 'XNAT.app.activityTab.populateArchivalDetails', timeout = 1) {
        var activities = JSON.parse(XNAT.cookie.get(cookieTag) || "{}"),
            key = (new Date()).toISOString().replace(/[^\w]/gi, '');
        var item = {
            title: title,
            statusListenerId: statusListenerId,
            divId: 'key' + key,
            detailsTag: 'div#activity-details-' + key,
            callbackPath: callbackPath,
            timeout: timeout
        };
        activities[key] = item;
        XNAT.cookie.set(cookieTag, activities, {});
        activityTab.startPoll(item, key);
    };

    activityTab.startPoll = function(item, key) {
        var $tab = $('#activity-tab');
        createEntry(item, key, $tab);
        activityTab.pollers[key] = true;
        checkProgress(item, key, 0);
        $tab.css('visibility', 'visible');
    };

    activityTab.stopPoll = function(key, succeeded) {
        delete activityTab.pollers[key];

        var $info = $('#activity-tab #key' + key);
        if (succeeded) {
            $info.addClass('text-success').prepend('<i class="fa fa-check" style="margin-right:3px"></i>');
        } else {
            $info.addClass('text-error').prepend('<i class="fa fa-minus-circle" style="margin-right:3px"></i>');
        }
        $info.find('#close' + key).show();
        $info.find('#details' + key).html('<i class="fa fa-expand"></i>');
    };

    activityTab.cancel = function(key, $row) {
        var activities = {};
        if (key) {
            activities = JSON.parse(XNAT.cookie.get(cookieTag) || "{}");
            delete activities[key];
            if ($row) {
                $row.parents('div.item').remove();
            }
        }
        if ($.isEmptyObject(activities)) {
            XNAT.cookie.remove(cookieTag);
            const tab = $('#activity-tab');
            tab.css('visibility', 'hidden');
            tab.find('div.item').remove();
        } else {
            XNAT.cookie.set(cookieTag, activities, {});
        }
    };

    function createEntry(item, key, parent) {
        parent.find('.panel-body').append('<div id="' + item.divId + '" class="item">' + item.title +
            '<div class="actions">' +
            '<a id="close' + key + '" class="icn close"><i class="fa fa-close"></i></a>' +
            '<a id="details' + key + '" class="icn details"><i class="fa fa-cog fa-spin"></i></a>' +
            '</div></div>');

        var details = XNAT.ui.dialog.init({
            title: item.title,
            content: spawn(item.detailsTag),
            destroyOnClose: false,
            protected: true,
            buttons: [
                {
                    label: 'Close',
                    isDefault: true,
                    close: true
                }
            ]
        });

        $(document).on('click', 'a#details' + key, function() {
            details.show();
        });
        $(document).on('click', 'a#close' + key, function() {
            activityTab.cancel(key, $(this));
        });
    }

    function checkProgress(item, key, errCnt, lastProgressIdx = -1) {
        if (! (activityTab.pollers.hasOwnProperty(key) && activityTab.pollers[key])) {
            return;
        }
        const statusListenerId = item.statusListenerId,
            detailsTag = item.detailsTag,
            itemDivId = '#' + item.divId;
        XNAT.xhr.getJSON({
            url: XNAT.url.restUrl('/xapi/event_tracking/' + statusListenerId),
            success: function(respDat) {
                var succeeded = null;
                try {
                    const callback = getCallbackForItem(item);
                    [succeeded, lastProgressIdx] = callback(itemDivId, detailsTag, respDat, lastProgressIdx);
                } catch (e) {
                    console.log(e);
                    processError(item, key, errCnt, e.name + ' (js): ' + e.message, lastProgressIdx);
                    return;
                }

                if (succeeded !== null) {
                    activityTab.stopPoll(key, succeeded);
                } else {
                    window.setTimeout(function() {
                        checkProgress(item, key, 0, lastProgressIdx);
                    }, item.timeout);
                }
            },
            error: function(xhr) {
                processError(item, key, errCnt,
                    xhr.responseText ? ': ' + xhr.responseText : '', lastProgressIdx);
            }
        });
    }

    function processError(item, key, errCnt, errDetails, lastProgressIdx) {
        if (errCnt < 2) {
            setTimeout(function() {
                checkProgress(item, key, ++errCnt, lastProgressIdx);
            }, 2000);
        } else {
            var msg = 'Issue polling event progress: ' + errDetails + '. Refresh the page to try again.';
            $(item.detailsTag).append('<div class="prog error">' + msg + '</div>');
            activityTab.stopPoll(key, false);
        }
    }

    activityTab.populateArchivalDetails = function(itemDivId, detailsTag, jsonobj, lastProgressIdx) {
        const succeeded = jsonobj['succeeded'];
        const payload = JSON.parse(jsonobj['payload']);
        let messages = "";
        let entryList = payload['entryList'] || [];
        if (entryList.length === 0 && succeeded == null) {
            return [null, lastProgressIdx];
        }
        entryList.forEach(function(e, i) {
            if (i <= lastProgressIdx) {
                return;
            }
            let level = e.status;
            let message = e.message.charAt(0).toUpperCase() + e.message.substr(1);
            let clazz;
            switch (level) {
                case 'Waiting':
                case 'InProgress':
                    clazz = 'info';
                    break;
                case 'Warning':
                    clazz = 'warning';
                    break;
                case 'Failed':
                    clazz = 'error';
                    break;
                case 'Completed':
                    clazz = 'success';
                    break;
            }
            messages += '<div class="prog ' + clazz + '">' + message + '</div>';
            lastProgressIdx = i;
        });
        if (succeeded != null) {
            messages += parseFinalMessage(jsonobj['finalMessage'], succeeded)
        }
        if (messages) {
            $(detailsTag).append(messages);
        }
        return [succeeded, lastProgressIdx];
    };

    function parseFinalMessage(message, succeeded) {
        const prearchiveLink = '<a target="_blank" href="' +
            XNAT.url.fullUrl('/app/template/XDATScreen_prearchives.vm') +
            '">prearchive</a>';

        if (succeeded) {
            const dest = message.replace(/:.*/, '');
            const urls = message.replace(/^.*:/, '').split(';');
            let urlsHtml;
            if (dest.toLowerCase().includes('prearchive')) {
                urlsHtml = 'Visit the ' + prearchiveLink + ' to review.';
            } else {
                urlsHtml = $.map(urls, function (url) {
                    var id = url.replace(/.*\//, '');
                    return '<a target="_blank" href="/data' + url + '">' + id + '</a>'
                }).join(', ');
            }
            return '<div class="prog success">' + urls.length +
                ' session(s) successfully uploaded to ' + dest + ': ' + urlsHtml + '</div>';
        } else {
            return '<div class="prog error">Extraction/Review failed: ' + message + '</div>' +
                '<div class="warning">Check the ' + prearchiveLink +
                ', your data may be available there for manual review.</div>';
        }
    }

    function getCallbackForItem(item) {
        const parts = item.callbackPath.split('.');
        let callback = parts.shift() === 'XNAT' ? XNAT : window;
        parts.forEach(function(p) {
           callback = callback[p];
        });
        return callback;
    }

    // don't init until the page is finished loading
    $(window).on('load', function(){
        activityTab.init();
        $(document).on('click', '#activity-tab a.activity-min', function() {
            $('#activity-tab .panel-body').hide();
            $('#activity-tab .panel-header a.activity-min').hide();
            $('#activity-tab .panel-header a.activity-max').show();
        });
        $(document).on('click', '#activity-tab a.activity-max', function() {
            $('#activity-tab .panel-body').show();
            $('#activity-tab .panel-header a.activity-max').hide();
            $('#activity-tab .panel-header a.activity-min').show();
        });
        $(document).on('click', '#activity-tab a.activity-close', function() {
            activityTab.pollers = {};
            activityTab.cancel();
        });
    });

    return XNAT.app.activityTab = activityTab;
}));
