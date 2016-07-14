// interactions with 'Security Passwords' section of admin ui
(function(){
    var fieldInterval, fieldDate, sdtDisabled, sdtInterval, sdtDate, openCal;
    setTimeout(function(){
      fieldInterval = $('#passwordExpirationInterval');
      fieldDate = $('#passwordExpirationDate');
      fieldDate.attr('placeholder', 'MM/DD/YYYY');
      openCal = $('#openCal-passwordExpirationDate');
      openCal.click(openCalendar);
//      fieldInterval[0].style.width = '40px';
//      fieldInterval[0].style.textAlign = 'right';
      fieldInterval[0].style.marginTop='10px';
      fieldDate[0].style.width = '90px';
      fieldDate[0].style.marginTop='10px';
      fieldDate.datetimepicker({
        timepicker:false,
        format:'m/d/Y',
        maxDate:'1970/01/01' // today is max date, disallow future date selection
      });
      sdtDisabled = $('#passwordExpirationTypeDisabled');
      sdtInterval = $('#passwordExpirationTypeInterval');
      sdtDate = $('#passwordExpirationTypeDate');
      sdtDisabled.click(changePasswordExpirationType);
      sdtInterval.click(changePasswordExpirationType);
      sdtDate.click(changePasswordExpirationType);
      changePasswordExpirationType(XNAT.data.siteConfig.passwordExpirationType);
    }, 1);

    function openCalendar(){
      fieldDate.datetimepicker('show');
    };

    function changePasswordExpirationType(eventOrValue){
        var value = eventOrValue;
        if (typeof eventOrValue === 'object') {
            if (eventOrValue.target.id == "passwordExpirationTypeDisabled") {
                value = 'Disabled';
            } else if (eventOrValue.target.id == "passwordExpirationTypeInterval") {
                value = 'Interval';
            }
            else {
                value = 'Date';
            }
        }
        sdtDisabled.val(value);
        sdtInterval.val(value);
        sdtDate.val(value);

        var interval = $('div.input-bundle.interval');
        var intervalUnits = $('span#intervalUnits');
        var date = $('div.input-bundle.date');
        var datePicker = $('span#datePicker');

        if (value == 'Disabled') {
            sdtDisabled.prop('checked', true);
            interval.val(-1);
            interval.hide();
            intervalUnits.hide();
            date.hide();
            datePicker.hide();
        } else if (value == 'Interval') {
            sdtInterval.prop('checked', true);
            interval.show();
            intervalUnits.show();
            date.hide();
            datePicker.hide();
        } else {
            sdtDate.prop('checked', true);
            date.show();
            datePicker.show();
            interval.hide();
            intervalUnits.hide();
        }
    }
})();
