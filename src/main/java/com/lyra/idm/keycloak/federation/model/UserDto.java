package com.lyra.idm.keycloak.federation.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserDto {
    private String firstName;
    private String lastName;
    private String userName;
    private String email;
    private boolean enabled; //false
    private Set<String> roles;
    private Map<String, List<String>> attributes;
    private String password;
}
