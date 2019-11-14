package com.lyra.idm.keycloak.federation.api.user;

import com.lyra.idm.keycloak.federation.model.UserDto;
import org.jboss.resteasy.annotations.Body;
import org.jboss.resteasy.annotations.ResponseObject;
import org.jboss.resteasy.annotations.Status;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;

import javax.ws.rs.HeaderParam;
import java.util.Set;

@ResponseObject
public interface UserResponseObject {

    @HeaderParam("X-Page")
    String page();
    @HeaderParam("X-Total-Pages")
    String totalPages();
    @HeaderParam("X-Per-Page")
    String perPage();
    @Body
    String body();

    ClientResponse response();
}
