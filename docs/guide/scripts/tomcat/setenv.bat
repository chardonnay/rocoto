@echo off
rem %CATALINA_BASE%\bin\setenv.bat - enables OCSP stapling for JSSE based connectors (NIO/NIO2).
rem Windows counterpart of setenv.sh. Review and adapt before use. Not executed as part of Rocoto's
rem automated tests. A Tomcat that runs as a Windows service ignores this file: add the options
rem with tomcat10w.exe (tab "Java", "Java Options", one option per line) instead.

set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.server.enableStatusRequestExtension=true"

rem Optional tuning (JDK defaults shown)
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.responseTimeout=5000"
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.cacheSize=256"
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.cacheLifetime=3600"
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.responderURI=http://ocsp.example.com"
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.responderOverride=false"
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Djdk.tls.stapling.ignoreExtensions=false"

rem Only if client certificates are verified by the connector with revocationEnabled="true":
rem set "CATALINA_OPTS=%CATALINA_OPTS% -Dcom.sun.security.enableCRLDP=true"
rem and in a java.security override file:  ocsp.enable=true
