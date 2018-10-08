package com.lyra.idm.keycloak.federation.api.user;


import com.lyra.idm.keycloak.federation.model.UserDto;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Date;
import java.util.List;

/**
 * Stub Service class to be used with RestEasy to access user rest api
 */
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface UserService {
    String FORMAT = "yyyy-MM-dd'T'HH:mm'Z'";

    @GET
    @Path("/full")
    List<UserDto> getUsers();

    /**
     * @param date see FORMAT in UTC
     */
    @GET
    @Path("/updated/{from}")
    List<UserDto> getUpdatedUsers( @PathParam("from")  String date);
}
