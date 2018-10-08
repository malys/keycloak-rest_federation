/*
 * Copyright 2015 Smartling, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lyra.idm.keycloak.federation.provider;

import com.lyra.idm.keycloak.federation.api.user.UserRepository;
import com.lyra.idm.keycloak.federation.api.user.UserService;
import com.lyra.idm.keycloak.federation.model.UserDto;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang.StringUtils;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Remote user federation provider factory.
 * <p>
 * http://www.keycloak.org/docs/3.4/server_development/
 */
@JBossLog
public class RestUserFederationProviderFactory implements UserStorageProviderFactory<RestUserFederationProvider>, ImportSynchronization {


    public static final String PROVIDER_NAME = "Rest User Federation";

    public static final String PROPERTY_URL = "url";
    public static final String ATTR_SYNC = "attr_sync";
    public static final String ROLE_SYNC = "role_sync";
    public static final String PREFIX = "prefix";
    public static final String UPPERCASE = "uppercase";
    public static final String PROXY_ENABLED = "proxy_enabled";
    public static final String PROXY_HOST = "proxyHost";
    public static final String PROXY_PORT = "proxyPort";
    public static final String UNCHECK_FEDERATION = "uncheck_federation";
    public static final String RESET_ACTIONS = "reset_action";
    public static final String NOT_CREATE_USERS = "not_create_users";
    public static final String BY_PASS = "by_pass";



    private static final TimeZone TZ = TimeZone.getTimeZone("UTC");
    private static final DateFormat DF = new SimpleDateFormat(UserService.FORMAT); // Quoted "Z" to indicate UTC, no timezone offset

    protected static final List<ProviderConfigProperty> configMetadata;

    /***
     * Format date to ISO
     * @param date
     * @return
     */
    public static String formatDate(Date date ){
        DF.setTimeZone(TZ);
        return DF.format(date);
    }

    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property().name(BY_PASS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("By-pass")
                .helpText("Disabling federation based on context. ex: ${COLLECT_DISABLE_FEDERATION} or 'true'")
                .add()
                .property().name(PROPERTY_URL)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Remote User Information Url")
                .defaultValue("https://")
                .helpText("Remote repository url")
                .add()
                .property().name(PREFIX)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Define prefix for roles and attributes")
                .helpText("size >=3.")
                .add()
                .property().name(UPPERCASE)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Uppercase role/attribute name")
                .helpText("Convert remote roles/attributes to uppercase")
                .add()
                .property().name(ROLE_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable roles synchronization")
                .helpText("Apply and create remote roles")
                .add()
                .property().name(ATTR_SYNC)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("true")
                .label("Enable attributes synchronization")
                .helpText("Apply remote attributes")
                .add()
                .property().name(UNCHECK_FEDERATION)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .label("Uncheck federation origin")
                .helpText("Change attributes or roles without controlling federation origin.")
                .add()
                .property().name(NOT_CREATE_USERS)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .defaultValue("false")
                .label("Not create new users")
                .helpText("Only update existed users")
                .add()
                .property().name(RESET_ACTIONS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Actions to apply after user creation")
                .helpText("ex: UPDATE_PASSWORD,VERIFY_EMAIL")
                .defaultValue("")
                .add()
                .property().name(PROXY_ENABLED)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use Proxy")
                .helpText("Add Java Properties: http(s).proxyHost,http(s).proxyPort")
                .add()
                .build();
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel config) throws ComponentValidationException {
        final String url = EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(PROPERTY_URL));
        final String prefix = EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(PREFIX));
        final Boolean roleIsSync = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(ROLE_SYNC)));
        final Boolean attributeIsSync = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(ATTR_SYNC)));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(PROXY_ENABLED)));
        final List<String> resetActions = formatResetActions(config.getConfig().getFirst(RESET_ACTIONS));

        boolean valid = true;
        String comment = "";

        final List<String> RESET_ACTIONS_LIST = Stream.of(UserModel.RequiredAction.values())
                .map(Enum::name)
                .collect(Collectors.toList());


        if (config.getConfig().getFirst(BY_PASS) != null) {
            try {
                EnvSubstitutor.envSubstitutor.replace(config.getConfig().getFirst(BY_PASS));
            } catch (IllegalArgumentException e) {
                valid = false;
                comment = "By pass parameter '" + config.getConfig().getFirst(BY_PASS).replaceAll("[${}]", "") + "' not exists.";

            } catch (Exception e) {
                valid = false;
                comment = "Please check by pass parameter.";
            }
        }

        List<String> notFound = resetActions.stream().filter(a -> !"".equals(a) && !RESET_ACTIONS_LIST.contains(a)).collect(Collectors.toList());
        if (notFound.size() > 0) {
            valid = false;
            comment = "Please check actions: " + String.join("", notFound);
        }


        if (url != null && url.length() < 10) {
            valid = false;
            comment = "Please check the url.";
        }

        if ((roleIsSync || attributeIsSync) && prefix != null && prefix.length() < 3) {
            valid = false;
            comment = "Please check prefix size.";
        }

        if ((roleIsSync || attributeIsSync) && prefix == null) {
            valid = false;
            comment = "Please define prefix.";
        }

        if (proxyOn && System.getProperty("http." + PROXY_HOST) == null && System.getProperty("https." + PROXY_HOST) == null) {
            valid = false;
            comment = "Please check 'http(s).proxyHost' property.";
        }

        log.debugf("validating module config %s", valid);

        if (!valid) {
            throw new ComponentValidationException("Invalid configuration. " + comment);
        }

    }

    @Override
    public String getId() {
        return PROVIDER_NAME;
    }

    @Override
    public String getHelpText() {
        return "This is a read-only user federation provider that can be used to sync user data one way from a remote REST API";
    }

    @Override
    public RestUserFederationProvider create(KeycloakSession session, ComponentModel model) {
        final String url = EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(PROPERTY_URL));
        final Boolean attributesIsSync = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(ATTR_SYNC)));
        final String rolePrefix = EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(PREFIX));
        final Boolean roleIsSync = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(ROLE_SYNC)));
        final Boolean upperCase = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(UPPERCASE)));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(PROXY_ENABLED)));
        final Boolean uncheckFederation = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(UNCHECK_FEDERATION)));
        final Boolean notCreateUsers = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(model.getConfig().getFirst(NOT_CREATE_USERS)));
        final List<String> resetActions = formatResetActions(model.getConfig().getFirst(RESET_ACTIONS));

        UserRepository repository = new UserRepository(url, proxyOn);
        return new RestUserFederationProvider(session, model, repository, roleIsSync,
                rolePrefix, upperCase, attributesIsSync,
                proxyOn, uncheckFederation, resetActions,
                notCreateUsers
        );
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.empty(), sessionFactory, realmId, model);
    }

    @Override
    public SynchronizationResult syncSince(Date date, KeycloakSessionFactory sessionFactory, String realmId, UserStorageProviderModel model) {
        return syncImpl(Optional.of(date), sessionFactory, realmId, model);
    }

    private List<String> formatResetActions(String resetActions) {
        final String SEP = ",";
        List<String> result;

        if (!StringUtils.isBlank(resetActions)) {
            String value = resetActions == null ? "" : resetActions;
            result = Arrays.asList(value.split(SEP)).stream().map(String::trim).collect(Collectors.toList());
        } else {
            result = new ArrayList<>();
        }
        return result;
    }


    protected SynchronizationResult syncImpl(Optional<Date> date, KeycloakSessionFactory sessionFactory, final String realmId, final ComponentModel fedModel) {
        final String url = EnvSubstitutor.envSubstitutor.replace(fedModel.getConfig().getFirst(PROPERTY_URL));
        final Boolean proxyOn = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(fedModel.getConfig().getFirst(PROXY_ENABLED)));
        final Boolean uncheck = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(fedModel.getConfig().getFirst(UNCHECK_FEDERATION)));
        final Boolean notCreateUsers = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(fedModel.getConfig().getFirst(NOT_CREATE_USERS)));

        UserRepository repository = new UserRepository(url, proxyOn);
        final SynchronizationResult syncResult = new SynchronizationResult();
        List<UserDto> users;

        Boolean byPass = false;
        try {
            byPass = Boolean.valueOf(EnvSubstitutor.envSubstitutor.replace(fedModel.getConfig().getFirst(BY_PASS)));
        } catch (IllegalArgumentException e) {
            log.warn("By pass parameter '" + fedModel.getConfig().getFirst(BY_PASS).replaceAll("[${}]", "") + "' not exists.");
        }

        if (!byPass) {
            //Federation enabled
            if (date.isPresent()) {

                users = repository.getUpdatedUsers(formatDate(date.get()));
            } else {
                //Every
                users = repository.getUsers();
            }

            class BooleanHolder {
                private boolean value = true;
            }
            final BooleanHolder exists = new BooleanHolder();

            if(users==null){
                log.infof("Federation starting for '%s' users", users.size());
            }else{
                log.errorf("Users is null");
            }

            for (final UserDto restUser : users) {

                try {
                    // Process each user in it's own transaction to avoid global fail
                    KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {
                        @Override
                        public void run(KeycloakSession session) {
                            RestUserFederationProvider restFedProvider = (RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
                            RealmModel currentRealm = session.realms().getRealm(realmId);

                            String username = restUser.getUserName();
                            exists.value = true;
                            UserModel currentUser = session.userLocalStorage().getUserByUsername(username, currentRealm);

                            if (currentUser == null) {

                                if (!notCreateUsers) {

                                    UserModel storageCurrentUser = session.userStorageManager().getUserByUsername(username, currentRealm);

                                    if (storageCurrentUser != null) {
                                        //He's in DB
                                        UserCache userCache = session.userCache();
                                        if (userCache != null) {
                                            userCache.evict(currentRealm, storageCurrentUser);
                                        }
                                        log.debugf("User %s exists. Evict him", currentUser.getUsername());

                                    } else {

                                        // Add new user to Keycloak
                                        exists.value = false;
                                        restFedProvider.importUserFromRest(session, currentRealm, restUser, uncheck);
                                        syncResult.increaseAdded();
                                    }

                                } else {
                                    log.debugf("notCreateUsers mode: Skip this users " + username);
                                }
                            } else {
                                //Uncheck mode ignore federation origin
                                if ((fedModel.getId().equals(currentUser.getFederationLink()) || uncheck) && restUser.getUserName().equals(currentUser.getUsername())) {

                                    // Update keycloak user
                                    restFedProvider.updateUserFromRest(currentRealm, restUser, currentUser, uncheck);

                                    session.userCache().evict(currentRealm, currentUser);
                                    log.debugf("Updated user from REST: %s", currentUser.getUsername());
                                    syncResult.increaseUpdated();
                                } else {
                                    log.warnf("User '%s' is not updated during sync as he already exists in Keycloak database but is not linked to federation provider '%s'", username, fedModel.getName());
                                    syncResult.increaseFailed();
                                }
                            }
                        }

                    });
                } catch (ModelException me) {
                    log.warn("Failed during import user from REST", me);
                    syncResult.increaseFailed();

                    // Remove user if we already added him during this transaction
                    if (!exists.value) {
                        KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {

                            @Override
                            public void run(KeycloakSession session) {
                                //RestUserFederationProvider restFedProvider = (RestUserFederationProvider) session.getProvider(UserStorageProvider.class, fedModel);
                                RealmModel currentRealm = session.realms().getRealm(realmId);

                                if (restUser.getUserName() != null) {
                                    UserModel existing = session.userLocalStorage().getUserByUsername(restUser.getUserName(), currentRealm);
                                    if (existing != null) {
                                        UserCache userCache = session.userCache();
                                        if (userCache != null) {
                                            userCache.evict(currentRealm, existing);
                                        }
                                        session.userLocalStorage().removeUser(currentRealm, existing);
                                    }
                                }
                            }
                        });
                    }
                }
            }
        } else {
            //Federation by passed
            log.infof("By Pass Federation '%s'", PROVIDER_NAME);
        }
        return syncResult;
    }
}
