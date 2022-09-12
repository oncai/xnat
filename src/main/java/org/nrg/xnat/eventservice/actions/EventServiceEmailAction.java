package org.nrg.xnat.eventservice.actions;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.nrg.mail.api.MailMessage;
import org.nrg.mail.services.MailService;
import org.nrg.xapi.model.users.User;
import org.nrg.xdat.security.helpers.UserHelper;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserHelperServiceI;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xdat.security.user.exceptions.UserInitException;
import org.nrg.xdat.security.user.exceptions.UserNotFoundException;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.eventservice.events.EventServiceEvent;
import org.nrg.xnat.eventservice.model.ActionAttributeConfiguration;
import org.nrg.xnat.eventservice.model.Subscription;
import org.nrg.xnat.eventservice.services.SubscriptionDeliveryEntityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.mail.MessagingException;
import java.util.*;
import java.util.stream.Collectors;

import static org.nrg.xnat.eventservice.entities.TimedEventStatusEntity.Status.*;

@Slf4j
//@Service
public class EventServiceEmailAction extends SingleActionProvider {

    private static final String FROM_KEY    = "from";
    private static final String TO_KEY      = "to";
    private static final String CC_KEY      = "cc";
    private static final String BCC_KEY     = "bcc";
    private static final String SUBJECT_KEY = "subject";
    private static final String BODY_KEY    = "body";

    private final String                                    displayName = "Email Action";
    private final String                                    description = "Project owners and site administrators can send an email in response to events.";
    private       Map<String, ActionAttributeConfiguration> attributes;
    private       Boolean                                   enabled     = true;

    private final MailService                       mailService;
    private final SubscriptionDeliveryEntityService subscriptionDeliveryEntityService;
    private final NamedParameterJdbcTemplate        jdbcTemplate;
    private final RoleHolder                        roleHolder;
    private final UserManagementServiceI            userManagementService;

    @Autowired
    public EventServiceEmailAction(final MailService mailService,
                                   final SubscriptionDeliveryEntityService subscriptionDeliveryEntityService,
                                   final NamedParameterJdbcTemplate jdbcTemplate,
                                   final RoleHolder roleHolder,
                                   final UserManagementServiceI userManagementService) {
        this.mailService = mailService;
        this.subscriptionDeliveryEntityService = subscriptionDeliveryEntityService;
        this.jdbcTemplate = jdbcTemplate;
        this.roleHolder = roleHolder;
        this.userManagementService = userManagementService;
    }


    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, ActionAttributeConfiguration> getAttributes(String projectId, UserI user) {

        if (!isUserAdmin(user)) {
            if (Strings.isNullOrEmpty(projectId)) {
                log.error("Email attributes for blank project unavailable for non-admin.");
                return null;
            } else if (!isUserOwner(user, projectId)) {
                log.error("Email attributes unavailable for non-project owner on: " + projectId);
                return null;
            }
        }

        Map<String, ActionAttributeConfiguration>                             attributeConfigurationMap = new HashMap<>();
        Map<String, List<ActionAttributeConfiguration.AttributeContextValue>> emailList                 = getRecipientsListAsAttributes(projectId);
        //attributeConfigurationMap.put(FROM_KEY,
        //        ActionAttributeConfiguration.builder()
        //                                    .description("Email originator.")
        //                                    .type("string")
        //                                    .defaultValue(user.getEmail())
        //                                    .userSettable(false)
        //                                    .required(true)
        //                                    .build());

        attributeConfigurationMap.put(TO_KEY,
                                      ActionAttributeConfiguration.builder()
                                                                  .description("Comma separated list of email recipients.")
                                                                  .type("string")
                                                                  .defaultValue("")
                                                                  .restrictTo(emailList)
                                                                  .required(true)
                                                                  .build());

        attributeConfigurationMap.put(CC_KEY,
                                      ActionAttributeConfiguration.builder()
                                                                  .description("Comma separated list of email recipients.")
                                                                  .type("string")
                                                                  .defaultValue("")
                                                                  .restrictTo(emailList)
                                                                  .required(false)
                                                                  .build());

        attributeConfigurationMap.put(BCC_KEY,
                                      ActionAttributeConfiguration.builder()
                                                                  .description("Comma separated list of email recipients.")
                                                                  .type("string")
                                                                  .defaultValue("")
                                                                  .restrictTo(emailList)
                                                                  .required(false)
                                                                  .build());

        attributeConfigurationMap.put(SUBJECT_KEY,
                                      ActionAttributeConfiguration.builder()
                                                                  .description("Email message subject.")
                                                                  .type("string")
                                                                  .defaultValue("")
                                                                  .required(false)
                                                                  .build());

        attributeConfigurationMap.put(BODY_KEY,
                                      ActionAttributeConfiguration.builder()
                                                                  .description("Textual body of email.")
                                                                  .type("string")
                                                                  .defaultValue("")
                                                                  .required(true)
                                                                  .build());

        return attributeConfigurationMap;
    }

    @Override
    public void processEvent(EventServiceEvent event, Subscription subscription, UserI user, final Long deliveryId) {

        log.debug("Attempting to send email with EventServiceEmailAction.");
        final Map<String, String> inputValues = subscription.attributes() != null ? subscription.attributes() : Maps.newHashMap();

        MailMessage mailMessage = new MailMessage();


        // String from = inputValues.get(FROM_KEY);
        String from = user.getEmail();

        List<String> toUsersList = Splitter.on(CharMatcher.anyOf(";:, "))
                                           .trimResults().omitEmptyStrings()
                                           .splitToList(inputValues.get(TO_KEY) != null ? inputValues.get(TO_KEY) : "");
        List<String> ccUsersList = Splitter.on(CharMatcher.anyOf(";:, "))
                                           .trimResults().omitEmptyStrings()
                                           .splitToList(inputValues.get(CC_KEY) != null ? inputValues.get(CC_KEY) : "");
        List<String> bccUsersList = Splitter.on(CharMatcher.anyOf(";:, "))
                                            .trimResults().omitEmptyStrings()
                                            .splitToList(inputValues.get(BCC_KEY) != null ? inputValues.get(BCC_KEY) : "");


        if (!areRecipientsAllowed(toUsersList, event.getProjectId(), user)) {
            failWithMessage(deliveryId, "TO: Recipients are not allowed for User: " + user.getLogin());
            return;
        } else if (!ccUsersList.isEmpty() && !areRecipientsAllowed(ccUsersList, event.getProjectId(), user)) {
            failWithMessage(deliveryId, "CC: Recipients are not allowed for User: " + user.getLogin());
            return;
        } else if (!bccUsersList.isEmpty() && !areRecipientsAllowed(bccUsersList, event.getProjectId(), user)) {
            failWithMessage(deliveryId, "BCC: Recipients are not allowed for User: " + user.getLogin());
            return;
        }


        if (Strings.isNullOrEmpty(from)) {
            failWithMessage(deliveryId, "Action missing \"from\" email attribute.");
            log.debug("Action missing \"from\" email attribute.");
            return;
        } else {
            mailMessage.setFrom(from);
        }


        List<String> toEmailList = getEmailList(toUsersList);
        if (toEmailList == null || toEmailList.isEmpty()) {
            failWithMessage(deliveryId, "Action missing email recipient as \"to\" attribute.");
            log.debug("Action missing email recipient as \"to\" attribute.");
            return;
        }
        mailMessage.setTos(toEmailList);

        List<String> ccEmailList = getEmailList(ccUsersList);
        if (ccEmailList != null && !ccEmailList.isEmpty()) {
            mailMessage.setCcs(ccEmailList);
        }

        List<String> bccEmailList = getEmailList(bccUsersList);
        if (bccEmailList != null && !bccEmailList.isEmpty()) {
            mailMessage.setBccs(bccEmailList);
        }

        if (!Strings.isNullOrEmpty(inputValues.get(SUBJECT_KEY))) {
            String subject = inputValues.get(SUBJECT_KEY);
            mailMessage.setSubject(subject);
        }

        String body = inputValues.get(BODY_KEY);
        if (Strings.isNullOrEmpty(body)) {
            failWithMessage(deliveryId, "Action missing \"body\" email attribute.");
            log.debug("Action missing \"body\" email attribute.");
            return;
        }
        mailMessage.setText(body);

        try {
            mailService.sendMessage(mailMessage);
            subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_COMPLETE, new Date(), "Email action completed successfully.");
        } catch (MessagingException e) {
            log.error("Email service failed to send message. \n" + e.getMessage());
            failWithMessage(deliveryId, "Email service failed to send message. \nCheck configuration");
        }


    }

    /* Get allowed emails and context */
    Map<String, List<ActionAttributeConfiguration.AttributeContextValue>> getRecipientsListAsAttributes(final String projectId) {
        final List<User> allowedRecipients = getAllowedRecipients(projectId);
        return allowedRecipients == null || allowedRecipients.isEmpty()
               ? Collections.emptyMap()
               : allowedRecipients.stream().filter(EventServiceEmailAction::isActiveUser).collect(Collectors.toMap(User::getUsername, EventServiceEmailAction::getAttributeContextValues));
    }

    private List<String> getEmailList(List<String> usernameList) {
        List<String> toEmailList = new ArrayList<>();
        for (String userName : usernameList) {
            try {
                toEmailList.add(userManagementService.getUser(userName).getEmail());
            } catch (UserNotFoundException | UserInitException e) {
                log.error("Could not load user: " + userName + "\n" + e.getStackTrace());
            }
        }
        return toEmailList;
    }

    private List<User> getAllowedRecipients(String projectId) {
        String QUERY;
        if (Strings.isNullOrEmpty(projectId)) {
            QUERY = "select * from xdat_user where xdat_user_id IN\n" +
                    "            (select groups_groupid_xdat_user_xdat_user_id from xdat_user_groupid where groupid IN\n" +
                    "                (select id from xdat_usergroup where xdat_usergroup_id IN\n" +
                    "                    (select xdat_usergroup_id from xdat_usergroup)))";
        } else {
            QUERY = "select * from xdat_user where xdat_user_id IN\n" +
                    "            (select groups_groupid_xdat_user_xdat_user_id from xdat_user_groupid where groupid IN\n" +
                    "                (select id from xdat_usergroup where xdat_usergroup_id IN\n" +
                    "                    (select xdat_usergroup_id from xdat_usergroup where tag = '" + projectId + "')))";
        }
        return jdbcTemplate.query(QUERY, USER_ROW_MAPPER);
    }

    private Boolean areRecipientsAllowed(List<String> recipientUserNames, String projectID, UserI actionUser) {
        if (isUserAdmin(actionUser)) {
            return true;
        } else if ((!Strings.isNullOrEmpty(projectID) && isUserOwner(actionUser, projectID))) {
            return getAllowedRecipients(projectID)
                    .stream().map(usr -> usr.getUsername())
                    .collect(Collectors.toList())
                    .containsAll(recipientUserNames);
        }
        return false;
    }

    void failWithMessage(Long deliveryId, String message) {
        subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_FAILED, new Date(), "Email action failed: " + message);
    }

    private void errorWithMessage(Long deliveryId, String message) {
        subscriptionDeliveryEntityService.addStatus(deliveryId, ACTION_ERROR, new Date(), "Email action error: " + message);
    }

    private Boolean isUserAdmin(UserI user) {
        return roleHolder.isSiteAdmin(user);
    }

    private Boolean isUserOwner(UserI user, String projectId) {
        final UserHelperServiceI userHelperService = UserHelper.getUserHelperService(user);
        return userHelperService.isOwner(projectId);
    }

    private static List<ActionAttributeConfiguration.AttributeContextValue> getAttributeContextValues(final User user) {
        return Arrays.asList(getAttributeContextValue("Email", user.getEmail()), getAttributeContextValue("Name", user.getFullName()), getAttributeContextValue("User", user.getUsername()));
    }

    private static ActionAttributeConfiguration.AttributeContextValue getAttributeContextValue(final String label, final String value) {
        return ActionAttributeConfiguration.AttributeContextValue.builder().label(label).type("string").value(value).build();
    }

    private static boolean isActiveUser(final User user) {
        return BooleanUtils.toBooleanDefaultIfNull(user.getEnabled(), false) && BooleanUtils.toBooleanDefaultIfNull(user.getVerified(), false);
    }

    public static final RowMapper<User> USER_ROW_MAPPER = (resultSet, index) -> {
        final int     userId    = resultSet.getInt("xdat_user_id");
        final String  username  = resultSet.getString("login");
        final String  firstName = resultSet.getString("firstname");
        final String  lastName  = resultSet.getString("lastname");
        final String  email     = resultSet.getString("email");
        final boolean enabled   = resultSet.getInt("enabled") == 1;
        final boolean verified  = resultSet.getInt("verified") == 1;
        return new User(userId, username, firstName, lastName, email, null, true, null, null, enabled, verified, null, null, null);
    };
}
