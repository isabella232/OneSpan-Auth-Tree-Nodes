/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2017-2018 ForgeRock AS.
 */
package com.os.tid.forgerock.openam.nodes;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.iplanet.sso.SSOException;
import com.os.tid.forgerock.openam.config.Constants;
import com.os.tid.forgerock.openam.models.HttpEntity;
import com.os.tid.forgerock.openam.utils.DateUtils;
import com.os.tid.forgerock.openam.utils.RestUtils;
import com.os.tid.forgerock.openam.utils.StringUtils;
import com.sun.identity.sm.SMSException;
import org.forgerock.json.JsonValue;
import org.forgerock.openam.auth.node.api.*;
import org.forgerock.openam.core.realms.Realm;
import org.forgerock.openam.sm.AnnotatedServiceRegistry;
import org.forgerock.util.i18n.PreferredLocales;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ResourceBundle;

/**
 * This node invokes the Check Session Status API, which checks the status of a request.
 *
 */
@Node.Metadata( outcomeProvider = OS_Auth_CheckSessionStatusNode.OSTIDCheckSessionStatusOutcomeProvider.class,
                configClass = OS_Auth_CheckSessionStatusNode.Config.class,
                tags = {"OneSpan", "basic authentication", "mfa", "risk"})
public class OS_Auth_CheckSessionStatusNode implements Node {
    private final Logger logger = LoggerFactory.getLogger("amAuth");
    private static final String BUNDLE = "com/os/tid/forgerock/openam/nodes/OS_Auth_CheckSessionStatusNode";
    private final OSConfigurationsService serviceConfig;

    /**
     * Configuration for the OS Auth Check Session Status Node.
     */
    public interface Config {

    }

    @Inject
    public OS_Auth_CheckSessionStatusNode(@Assisted Realm realm, AnnotatedServiceRegistry serviceRegistry) throws NodeProcessException {
        try {
            this.serviceConfig = serviceRegistry.getRealmSingleton(OSConfigurationsService.class, realm).get();
        } catch (SSOException | SMSException e) {
            throw new NodeProcessException(e);
        }
    }

    @Override
    public Action process(TreeContext context) {
        logger.debug("OS_Auth_CheckSessionStatusNode started");
        JsonValue sharedState = context.sharedState;
        String tenantName = serviceConfig.tenantNameToLowerCase();
        String environment = serviceConfig.environment().name();

        JsonValue eventExpiryJsonValue = sharedState.get(Constants.OSTID_EVENT_EXPIRY_DATE);
        JsonValue requestIdJsonValue = sharedState.get(Constants.OSTID_REQUEST_ID);
        CheckSessionStatusOutcome checkSessionStatusEnum;
        if (!requestIdJsonValue.isString() || requestIdJsonValue.asString().isEmpty()) {
            checkSessionStatusEnum = CheckSessionStatusOutcome.error;
            sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: Request ID is missing!");
        }else if(DateUtils.hasExpired(eventExpiryJsonValue.asString())){
            sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: Your session has timed out!");
            checkSessionStatusEnum = CheckSessionStatusOutcome.timeout;
        }else {
            try {
                HttpEntity httpEntity = RestUtils.doGet(StringUtils.getAPIEndpoint(tenantName,environment) + String.format(Constants.OSTID_API_CHECK_SESSION_STATUS,requestIdJsonValue.asString()));
                JSONObject checkSessionStatusResponseJSON = httpEntity.getResponseJSON();
                if(httpEntity.isSuccess()){
                    String sessionStatus = checkSessionStatusResponseJSON.getString("sessionStatus");
                    checkSessionStatusEnum = CheckSessionStatusOutcome.valueOf(sessionStatus);
                }else{
                    String message = checkSessionStatusResponseJSON.getString("message");
                    if(message == null){
                        throw new NodeProcessException("Fail to parse response: " + JSON.toJSONString(checkSessionStatusResponseJSON));
                    }
                    checkSessionStatusEnum = CheckSessionStatusOutcome.error;
                    sharedState.put(Constants.OSTID_ERROR_MESSAGE,message);
                }
            } catch (Exception e) {
                logger.debug("OS_Auth_CheckSessionStatusNode exception: " + e.getMessage());
                checkSessionStatusEnum = CheckSessionStatusOutcome.error;
                sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: Fail to check user's session status!");
            }
        }

        switch (checkSessionStatusEnum) {
            case pending:
                return goTo(CheckSessionStatusOutcome.pending).build();
            case accepted:
                return goTo(CheckSessionStatusOutcome.accepted).build();
            case refused:
                sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: End user refused to validate the event!");
                return goTo(CheckSessionStatusOutcome.refused).replaceSharedState(sharedState).build();
            case failure:
                sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: End user failed to validate the event!");
                return goTo(CheckSessionStatusOutcome.failure).replaceSharedState(sharedState).build();
            case timeout:
                sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: The session has been expired!");
                return goTo(CheckSessionStatusOutcome.timeout).replaceSharedState(sharedState).build();
            case unknown:
                sharedState.put(Constants.OSTID_ERROR_MESSAGE,"OneSpan Auth Check Session Status: The event validation status is unknown!");
                return goTo(CheckSessionStatusOutcome.unknown).replaceSharedState(sharedState).build();
            case error:
                return goTo(CheckSessionStatusOutcome.error).replaceSharedState(sharedState).build();
            default:
                return goTo(CheckSessionStatusOutcome.pending).build();
        }
    }

    private Action.ActionBuilder goTo(CheckSessionStatusOutcome outcome) {
        return Action.goTo(outcome.name());
    }

    public enum CheckSessionStatusOutcome {
        pending,
        accepted,
        refused,
        failure,
        timeout,
        unknown,
        error
    }

    /**
     * Defines the possible outcomes.
     */
    public static class OSTIDCheckSessionStatusOutcomeProvider implements OutcomeProvider {
        @Override
        public List<Outcome> getOutcomes(PreferredLocales locales, JsonValue nodeAttributes) {
            ResourceBundle bundle = locales.getBundleInPreferredLocale(OS_Auth_CheckSessionStatusNode.BUNDLE,
                    OSTIDCheckSessionStatusOutcomeProvider.class.getClassLoader());
            return ImmutableList.of(
                    new Outcome(CheckSessionStatusOutcome.pending.name(), bundle.getString("pendingOutcome")),
                    new Outcome(CheckSessionStatusOutcome.accepted.name(), bundle.getString("acceptedOutcome")),
                    new Outcome(CheckSessionStatusOutcome.refused.name(), bundle.getString("refusedOutcome")),
                    new Outcome(CheckSessionStatusOutcome.failure.name(), bundle.getString("failureOutcome")),
                    new Outcome(CheckSessionStatusOutcome.timeout.name(), bundle.getString("timeoutOutcome")),
                    new Outcome(CheckSessionStatusOutcome.unknown.name(), bundle.getString("unknownOutcome")),
                    new Outcome(CheckSessionStatusOutcome.error.name(), bundle.getString("errorOutcome"))
            );
        }
    }
}

