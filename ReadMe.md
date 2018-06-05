# RH-SSO federation

RH-SSO federation to synchronize users from **business REST API** to RH-SSO. 

## Versioning

Refer to [Semantic Versioning 2.0.0](http://semver.org/).

## Deployment on Maven repository

 ```bash
 mvn clean deploy -Pnexus
 ```
**Nexus** maven profile defines:

    <nexus.url.release>${nexus.url}/content/repositories/releases</nexus.url.release>
    <nexus.url.snapshot>${nexus.url}/content/repositories/snapshots</nexus.url.snapshot>

## Architecture

## Model

See [UserDto](./src/main/java/com/lyra/idm/keycloak/federation/model/UserDto.java)


## Class diagram

### Generation

```bash
 jar D:\prog\plantuml-dependency-cli-1.4.0-jar-with-dependencies.jar  -o h:\plantuml.txt -b D:\Developpement\archi\idm\rh-sso-federation\src -dp (?=.*\b(keycloak)\b)(?!.*\b(models)\b)(.+)
```

### Diagram 

![](./classDiagram.png)

## Dependencies

### RH-SSO

Change RH-SSO version in *pom.xml* and maven will update inherited dependencies.

```xml
    <parent>
        <groupId>com.lyra.idm.keycloak</groupId>
        <artifactId>parent</artifactId>
        <version>7.2.0.GA</version>
    </parent>
```

## Features

![](./federation.png)

See *help button* or [RestUserFederationProviderFactory](./src/main/java/com/lyra/idm/keycloak/federation/provider/RestUserFederationProviderFactory.java) for description.

## Release Notes

### 1.0.0

* users and  updated users synchronization
* Proxy management
* Role and attributes synchronization
* Prefix and upper case for roles

## Author

Sylvain M. for [Lyra](https://lyra.com).

