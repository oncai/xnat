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

    //TODO right now, this only monitors compressed uploader, but it should work for any async process, we just need
    // a unified way to poll progress

    var activityTab, cookieTag = 'activities';

    XNAT.app = getObject(XNAT.app || {});

    XNAT.app.activityTab = activityTab =
        getObject(XNAT.app.activityTab || {});

    activityTab.intervals = {};

    activityTab.init = function() {
        var activities = JSON.parse(XNAT.cookie.get(cookieTag) || "{}");
        $.each(activities, function(key, item) {
            activityTab.startPoll(item, key);
        });
    };

    activityTab.start = function(title, statusListenerId) {
        var activities = JSON.parse(XNAT.cookie.get(cookieTag) || "{}"),
            key = (new Date()).toISOString().replace(/[^\w]/gi, '');
        var item = {
            title: title,
            statusListenerId: statusListenerId,
            divId: 'key' + key,
            detailsTag: 'div#activity-details-' + key
        };
        activities[key] = item;
        XNAT.cookie.set(cookieTag, activities, {});
        activityTab.startPoll(item, key);
    };

    activityTab.startPoll = function(item, key) {
        var $tab = $('#activity-tab');
        createEntry(item, key, $tab);
        var $info = $tab.find(item.divId);
        activityTab.intervals[key] = window.setInterval(function(){
            checkImageArchivalProgress(item.statusListenerId, $info, key, item.detailsTag);
        }, 3000);
        $tab.css('visibility', 'visible');
    };

    activityTab.stopPoll = function(key, succeeded) {
        window.clearInterval(activityTab.intervals[key]);
        delete activityTab.intervals[key];

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
        }
        if ($.isEmptyObject(activities)) {
            XNAT.cookie.remove(cookieTag);
            $('#activity-tab').css('visibility', 'hidden');
        } else {
            XNAT.cookie.set(cookieTag, activities, {});
            $row.parents('div.item').hide();
        }
    };

    function createEntry(item, key, parent) {
        parent.find('.panel-body').append('<div id="' + item.divId + '" class="item">' + item.title +
            '<div class="actions">' +
            '<a id="details' + key + '" class="icn details"><i class="fa fa-cog fa-spin"></i></a>' +
            '<a id="close' + key + '" class="icn close"><i class="fa fa-close"></i></a>' +
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
            activityTab.cancel(key. $(this));
        });
    }

    function checkImageArchivalProgress(statusListenerId, div, key, detailsTag) {
        $.ajax({
            method: 'GET',
            url: XNAT.url.restUrl('/REST/status/' + statusListenerId,
                {format: 'json', stamp: (new Date()).getTime()}),
            success: function(respDat) {
                var message;
                // handle response
                var respPos = respDat.msgs[0].length - 1;
                if (respDat.msgs[0].length > 0) {
                    if (respDat.msgs[0].length > 0) {
                        message = respDat.msgs[0][respPos].msg;
                    }
                }
                var succeeded = populateDetails(div, detailsTag, message, respDat);
                if (succeeded !== null) {
                    activityTab.stopPoll(key, succeeded);
                }
            },
            error: function(xhr) {
                errorHandler(xhr, 'Issue polling archival progress');
            }
        });
    }

    function populateDetails(div, detailsTag, msg, jsonobj) {
        var messages = "", succeeded = null,
            prearchiveUrl = '<a target="_blank" href="' +
                XNAT.url.fullUrl('/app/template/XDATScreen_prearchives.vm') +
                '">prearchive</a>';
        try {
            var respPos = jsonobj.msgs[0].length - 1;
            for (var i = 0; i <= respPos; i++) {
                var level = jsonobj.msgs[0][i].status;
                var message = jsonobj.msgs[0][i].msg || level;
                message = message.charAt(0).toUpperCase() + message.substr(1);
                if (level === "COMPLETED") {
                    // Hack to indicate processing done
                    if (message.startsWith("XXX")) {
                        // stop polling
                        succeeded = true;

                        var dest = message.replace(/^XXX/, '').replace(/:.*/,'');
                        var urls = message.replace(/^XXX.*\/archive/, '/archive').split(';');
                        var urlsHtml;
                        if (dest.toLowerCase().includes('prearchive')) {
                            urlsHtml = 'Visit the ' + prearchiveUrl + ' to review.';
                        } else {
                            urlsHtml = $.map(urls, function(url) {
                                var id = url.replace(/.*\//, '');
                                return '<a target="_blank" href="/data' + url + '">' + id + '</a>'
                            }).join(', ');
                        }

                        msg = urls.length + ' session(s) successfully uploaded to ' + dest;
                        message = msg + ': ' + urlsHtml;
                    }
                    message = '<div class="prog success">' + message + '</div>';
                } else if (level === "PROCESSING") {
                    message = '<div class="prog info">' + message + '</div>';
                } else if (level === "WARNING") {
                    message = '<div class="prog warning">' + message + '</div>';
                } else if (level === "FAILED") {
                    // Hack to indicate processing done
                    if (message.startsWith("XXX")) {
                        // stop polling
                        succeeded = false;

                        message = message.replace(/^XXX/,'');
                        message = '<div class="prog error">Extraction/Review failed: ' + message + '</div>';
                        message += '<div class="warning">Check the ' + prearchiveUrl +
                            ', your data may be available there for manual review.</div>';
                    } else {
                        message = '<div class="prog error">' + message + '</div>';
                    }
                } else {
                    message = '<div class="prog info">' + message + '</div>';
                }
                messages += message;
            }
        } catch (e) {
            console.log(e);
        }
        $(detailsTag).html(messages);
        return succeeded;
    }

    function errorHandler(e, base) {
        var info = e.responseText ? base + ': ' + e.responseText : base;
        var details = spawn('p',[info]);
        console.log(e);
        xmodal.alert({
            title: 'Error',
            content: '<p><strong>Error ' + e.status + ': '+ e.statusText+'</strong></p>' + details.html,
            okAction: function () {
                xmodal.closeAll();
            }
        });
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
            $.each(activityTab.intervals, function(key, int) {
                window.clearInterval(int);
            });
            activityTab.intervals = {};
            activityTab.cancel();
        });
    });

    return XNAT.app.activityTab = activityTab;
}));
