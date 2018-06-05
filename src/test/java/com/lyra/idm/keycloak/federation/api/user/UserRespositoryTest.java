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
package com.lyra.idm.keycloak.federation.api.user;

import com.lyra.idm.keycloak.federation.model.UserDto;
import com.xebialabs.restito.server.StubServer;
import org.glassfish.grizzly.http.Method;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.xebialabs.restito.builder.stub.StubHttp.whenHttp;
import static com.xebialabs.restito.builder.verify.VerifyHttp.verifyHttp;
import static com.xebialabs.restito.semantics.Action.contentType;
import static com.xebialabs.restito.semantics.Action.resourceContent;
import static com.xebialabs.restito.semantics.Action.status;
import static com.xebialabs.restito.semantics.Condition.get;
import static com.xebialabs.restito.semantics.Condition.method;
import static com.xebialabs.restito.semantics.Condition.uri;
import static java.lang.String.format;

/**
 * Remote user federation provider factory tests.
 */
public class UserRespositoryTest {

    private static final String USER_NAME1 = "user1@test.com";
    private static final String USER_NAME2 = "user2@test.com";
    private static final String CONTEXT_USERS = "/users/";
    private static final String CONTEXT_UPDATED_USERS = "/users/updated/";


    private StubServer server;

    @Before
    public void setUp() throws Exception {
        server = new StubServer().run();
        System.out.println("Server listen on : "  +server.getPort());
    }

    @After
    public void stop() {
        server.stop();
    }


    private void check(UserDto user, String name){
        Assert.assertEquals(name, user.getEmail());
        Assert.assertTrue("is enabled", user.isEnabled());
        Assert.assertTrue("has admin role", user.getRoles().contains("admin"));
        Assert.assertTrue("has user role", user.getRoles().contains("user"));

        Map<String, List<String>> attribs = user.getAttributes();

        Assert.assertTrue("Contains locale", attribs.containsKey("locale"));
        Assert.assertTrue("Contains country", attribs.containsKey("country"));
        Assert.assertTrue("Contains client id", attribs.containsKey("clientId"));
        Assert.assertTrue("Contains phone Nr", attribs.containsKey("phoneNumber"));
        Assert.assertTrue("Contains mobile Number ", attribs.containsKey("mobileNumber"));
    }

    @Test
    public void testGetUsers() {
        whenHttp(server).
                match(get(CONTEXT_USERS)).
                then(status(HttpStatus.OK_200), contentType("application/json"), resourceContent("com.lyra.idm.keycloak.federation.api/users.json"));

        UserRepository userRepository = new UserRepository(getRestUrl(""),false);
        List<UserDto> users= userRepository.getUsers();

        verifyHttp(server).once(
                method(Method.GET),
                uri(CONTEXT_USERS)
        );

        check(users.get(0),USER_NAME1);
        check(users.get(1),USER_NAME2);


    }

    @Test
    public void testGetUpdatedUsers() {
        whenHttp(server).
                match(get(CONTEXT_UPDATED_USERS)).
                then(status(HttpStatus.OK_200), contentType("application/json"), resourceContent("com.lyra.idm.keycloak.federation.api/users.json"));

        UserRepository userRepository = new UserRepository(getRestUrl(""),false);
        List<UserDto> users= userRepository.getUpdatedUsers(new Date());

        verifyHttp(server).once(
                method(Method.GET),
                uri(CONTEXT_UPDATED_USERS)
        );

        check(users.get(0),USER_NAME1);
        check(users.get(1),USER_NAME2);
    }


    private String getRestUrl(String context) {
        return format("http://localhost:%d%s", server.getPort(), context);
    }
}