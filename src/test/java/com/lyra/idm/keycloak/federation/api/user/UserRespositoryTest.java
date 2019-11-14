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
import com.lyra.idm.keycloak.federation.provider.RestUserFederationProviderFactory;
import com.xebialabs.restito.server.StubServer;
import org.apache.log4j.BasicConfigurator;
import org.glassfish.grizzly.http.util.HttpStatus;
import org.junit.*;

import java.util.*;

import static com.xebialabs.restito.builder.stub.StubHttp.whenHttp;
import static com.xebialabs.restito.semantics.Action.*;
import static com.xebialabs.restito.semantics.Condition.*;
import static java.lang.String.format;

//TODO not executed with maven

/**
 * Remote user federation provider factory tests.
 */
public class UserRespositoryTest {

    private static final String USER_NAME1 = "user1@test.com";
    private static final String USER_NAME2 = "user2@test.com";
    private static final String CONTEXT_USERS = "/full";
    private static final String CONTEXT_UPDATED_USERS = "/updated";


    private static StubServer server;

    @BeforeClass
    public static void setUp() {
        BasicConfigurator.configure();
        server = new StubServer().run();
        whenHttp(server).
                match(get("/"))
                .then(contentType("text/html"), stringContent(":)"));
        whenHttp(server).
                match(startsWithUri(CONTEXT_USERS)).
                then(
                        status(HttpStatus.OK_200),
                        header("X-Page", "1"),
                        header("X-Total-Pages", "2"),
                        header("X-Per-Page", "200"),
                        contentType("application/json")
                )
                .withSequence(
                        resourceContent("com.lyra.idm.keycloak.federation.api/users.json"),
                        resourceContent("com.lyra.idm.keycloak.federation.api/users2.json")
                );
        whenHttp(server).
                match(startsWithUri(CONTEXT_UPDATED_USERS)).
                then(
                        status(HttpStatus.OK_200),
                        header("X-Page", "1"),
                        header("X-Total-Pages", "2"),
                        header("X-Per-Page", "200"),
                        contentType("application/json")
                )
                .withSequence(
                        resourceContent("com.lyra.idm.keycloak.federation.api/users.json"),
                        resourceContent("com.lyra.idm.keycloak.federation.api/users2.json")
                );
        System.out.println("Server listen on : " + server.getPort());
        /*
        while (true) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }*/
    }

    @AfterClass
    public static void stop() {
        server.stop();
    }


    private void check(UserDto user, String name) {
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
        UserRepository userRepository = new UserRepository(getRestUrl(""), false);
        Set<UserDto> users = userRepository.getUsers();

        Optional<UserDto> u1 = users.stream()
                .filter(x -> x.getEmail().equals(USER_NAME1))
                .findFirst();
        Optional<UserDto> u2 = users.stream()
                .filter(x -> x.getEmail().equals(USER_NAME2))
                .findFirst();

        if (u1.isPresent()) check(u1.get(), USER_NAME1);
        else Assert.assertTrue(false);

        if (u2.isPresent()) check(u2.get(), USER_NAME2);
        else Assert.assertTrue(false);

        Assert.assertEquals(users.size(), 4);
    }

    @Test
    public void testGetUpdatedUsers() {
        UserRepository userRepository = new UserRepository(getRestUrl(""), false);
        Set<UserDto> users = userRepository.getUpdatedUsers(RestUserFederationProviderFactory.formatDate(new Date()));

        Optional<UserDto> u1 = users.stream()
                .filter(x -> x.getEmail().equals(USER_NAME1))
                .findFirst();
        Optional<UserDto> u2 = users.stream()
                .filter(x -> x.getEmail().equals(USER_NAME2))
                .findFirst();

        if (u1.isPresent()) check(u1.get(), USER_NAME1);
        else Assert.assertTrue(false);

        if (u2.isPresent()) check(u2.get(), USER_NAME2);
        else Assert.assertTrue(false);

        Assert.assertEquals(users.size(), 4);
    }


    private String getRestUrl(String context) {
        return format("http://localhost:%d%s", server.getPort(), context);
    }
}