package com.lyra.idm.keycloak.federation.api.user;


import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

/**
 * Stub Service class to be used with RestEasy to access user rest api
 * Header	Description
 * X-Total-Pages	The total number of pages
 * X-Per-Page	The number of items per page
 * X-Page	The index of the current page (starting at 1)
 */
@Produces(MediaType.APPLICATION_JSON)
public interface UserServiceObject {

    @GET
    @Path("/full")
    UserResponseObject getUsers(@HeaderParam("X-Page") int page, @HeaderParam("X-Per-Page") int perPage);

    @GET
    @Path("/updated/{from}")
    UserResponseObject getUpdatedUsers(@PathParam("from") String date, @HeaderParam("X-Page") int page, @HeaderParam("X-Per-Page") int perPage);
}
