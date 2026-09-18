# Tomcat with users from the Tomcat realm

## Goal

Let Tomcat authenticate the callers - here with the `UserDatabaseRealm` and `tomcat-users.xml`
that every Tomcat ships, but any realm works (JNDI/LDAP, DataSource, JAAS). Rocoto takes the
principal from the container and asks it for the roles `rocoto-reader`, `rocoto-operator` and
`rocoto-admin`.

- `auth.mode: container` - only the realm counts; Rocoto's user store is ignored.
- `auth.mode: chain` - Rocoto's own credentials first (API tokens for machines), everything else
  goes to the realm.

There is no separate runnable project; this page builds on
[`examples/tomcat-builtin`](../../examples/tomcat-builtin/README.md).

## Prerequisites

- Tomcat 10.1 with an HTTPS connector (credentials require TLS), `rocoto.war` in `webapps/`
- `rocoto-tools.jar` if you want API tokens next to the realm (chain mode)

## Files

`$CATALINA_BASE/conf/tomcat-users.xml` - the role names must be exactly these:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<tomcat-users xmlns="http://tomcat.apache.org/xml" version="1.0">
  <role rolename="rocoto-reader"/>
  <role rolename="rocoto-operator"/>
  <role rolename="rocoto-admin"/>
  <!-- DEMO passwords. Use a digested CredentialHandler or an external realm in production. -->
  <user username="carl"  password="demo-password-123" roles="rocoto-admin"/>
  <user username="olive" password="demo-password-123" roles="rocoto-operator"/>
  <user username="ron"   password="demo-password-123" roles="rocoto-reader"/>
  <user username="rudi"  password="demo-password-123" roles="manager-gui"/>
</tomcat-users>
```

The default `server.xml` already contains the matching realm; nothing to change:

```xml
<Realm className="org.apache.catalina.realm.LockOutRealm">
  <Realm className="org.apache.catalina.realm.UserDatabaseRealm" resourceName="UserDatabase"/>
</Realm>
```

`$CATALINA_BASE/conf/rocoto.yaml`:

```yaml
auth:
  mode: chain                 # or: container
  builtin:
    usersFile: rocoto-users.yaml   # chain mode: holds the API tokens; may be missing in container mode

policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
```

`rocoto.war` needs no change: its `web.xml` declares `BASIC` as login method and the three roles,
and deliberately no security constraints - Rocoto's filter calls
`HttpServletRequest.authenticate()` itself.

## Commands

```sh
# chain mode: a token for Prometheus next to the realm users
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader \
     --file $CATALINA_BASE/conf/rocoto-users.yaml
chmod 600 $CATALINA_BASE/conf/rocoto-users.yaml

$CATALINA_BASE/bin/catalina.sh stop && $CATALINA_BASE/bin/catalina.sh start
```

## Expected output

```sh
B=https://localhost:8443/rocoto

curl -s --cacert ca.crt -u carl:demo-password-123 $B/api/v1/server
# ... "caller":{"name":"carl","roles":["rocoto-admin"],"authenticatedBy":"container"}

curl -s --cacert ca.crt -H "Authorization: Bearer $TOKEN" $B/api/v1/server        # chain mode only
# ... "caller":{"name":"token:prometheus","roles":["rocoto-reader"],"authenticatedBy":"token"}

curl -s -o /dev/null -w '%{http_code}\n' --cacert ca.crt $B/api/v1/server
# 401   (WWW-Authenticate: Basic ...)

# rudi is a valid Tomcat user without a Rocoto role: authenticated, but sees nothing
curl -s --cacert ca.crt -u rudi:demo-password-123 $B/api/v1/mbeans
# {"pattern":"*:*","mbeans":[]}
curl -s --cacert ca.crt -u rudi:demo-password-123 "$B/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose"
# {"status":403,"error":"Access denied by policy"}
```

This behavior is what Rocoto's integration test `ContainerAuthIT` asserts against an embedded
Tomcat realm (users `carl` and `rudi`); the standalone Tomcat files above were not executed.

## Notes

- In chain mode a user name that exists in `rocoto-users.yaml` is always authenticated by Rocoto;
  only unknown users are passed on to the realm.
- Rocoto asks the container only for roles it knows: the three standard roles plus every role
  named in `policy.readRoles` or `policy.rules`. To use your existing role names, list them there:

  ```yaml
  policy:
    readRoles: [rocoto-reader, rocoto-operator, rocoto-admin, monitoring]
  ```

- The brute-force protection for realm users is Tomcat's `LockOutRealm`; `auth.builtin.lockout`
  only covers Rocoto's own credentials.
- The MBeans of the user database (`Users:*`) are on Rocoto's built-in deny list for write and exec.

## What to look at next

- [tomcat-builtin-auth](tomcat-builtin-auth.md), [wildfly-basic-elytron](wildfly-basic-elytron.md)
- [Configuration reference](../reference/configuration.md): `auth.mode`, `auth.container.challenge`
