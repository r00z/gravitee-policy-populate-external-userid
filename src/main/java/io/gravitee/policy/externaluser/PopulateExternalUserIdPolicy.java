/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.externaluser;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApiRepository;
import io.gravitee.repository.management.api.MembershipRepository;
import io.gravitee.repository.management.api.SubscriptionRepository;
import io.gravitee.repository.management.api.UserRepository;
import io.gravitee.repository.management.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

import static io.gravitee.gateway.api.ExecutionContext.*;

@SuppressWarnings("unused")
public class PopulateExternalUserIdPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(PopulateExternalUserIdPolicy.class);
    static final String BEARER_AUTHORIZATION_TYPE = "Bearer";
    /**
     * The associated configuration to this PopulateExternalUserId Policy
     */
    private PopulateExternalUserIdPolicyConfiguration configuration;

    /**
     * Create a new PopulateExternalUserId Policy instance based on its associated configuration
     *
     * @param configuration the associated configuration to the new PopulateExternalUserId Policy instance
     */
    public PopulateExternalUserIdPolicy(PopulateExternalUserIdPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        SubscriptionRepository subscriptionRepository = executionContext.getComponent(SubscriptionRepository.class);
        ApiRepository apiRepository = executionContext.getComponent(ApiRepository.class);
        MembershipRepository membershipRepository = executionContext.getComponent(MembershipRepository.class);
        UserRepository userRepositoryRepository = executionContext.getComponent(UserRepository.class);

        String subscriptionId = (String) executionContext.getAttribute(ATTR_SUBSCRIPTION_ID);
        String apiId = (String) executionContext.getAttribute(ATTR_API);

        String clientId = getExternalUserId(subscriptionRepository, subscriptionId, apiRepository, apiId, membershipRepository, userRepositoryRepository);
        if (clientId != null) {
            executionContext.setAttribute(configuration.getAttributeName(), clientId);
        }

        // Finally continue chaining
        policyChain.doNext(request, response);
    }

    private String getExternalUserId(SubscriptionRepository subscriptionRepository, String subscriptionId,
                                     ApiRepository apiRepository, String apiId,
                                     MembershipRepository membershipRepository, UserRepository userRepositoryRepository) {
        try {
            Optional<Subscription> subscriptionOpt = subscriptionRepository.findById(subscriptionId);
            if (!subscriptionOpt.isPresent()) {
                return null;
            }
            Subscription subscription = subscriptionOpt.get();

            Optional<Api> apiOpt = apiRepository.findById(apiId);
            if (!apiOpt.isPresent()) {
                return null;
            }
            Api api = apiOpt.get();

            String currentUser = subscription.getSubscribedBy();
            LOGGER.debug("Subscription Current user: " + currentUser);

            boolean isOwner = isApiOwner(membershipRepository, api, currentUser);
            if (isOwner) {
                // API owner can assign externalUserIds himself instead of asking them to register in the portal
                LOGGER.debug("Current user is API owner - return configured ClientId from the application: " + subscription.getClientId());
                return subscription.getClientId();
            }

            Optional<User> userOpt = userRepositoryRepository.findById(currentUser);
            if (!userOpt.isPresent()) {
                return null;
            }
            LOGGER.debug("Return Current User Email: " + userOpt.get().getEmail());
            return userOpt.get().getEmail();
        } catch (TechnicalException te) {
            LOGGER.error("An unexpected error occurs while populating userId.", te);
        } catch (IllegalStateException ise) {
            LOGGER.error("An unexpected error occurs while populating userId. Most likely SubscriptionRepositoryWrapper is used where findById is not implemented ", ise);
        }
        return null;
    }

    private boolean isApiOwner(MembershipRepository membershipRepository, Api api, String currentUser) throws TechnicalException {
        Set<Membership> membershipsPO = membershipRepository.findByReferenceAndRole(MembershipReferenceType.API, api.getId(), RoleScope.API, "PRIMARY_OWNER");
        Set<Membership> membershipsO = membershipRepository.findByReferenceAndRole(MembershipReferenceType.API, api.getId(), RoleScope.API, "OWNER");
        membershipsO.addAll(membershipsPO);

        return membershipsO.stream().anyMatch(m -> currentUser.equals(m.getUserId()));
    }

}
