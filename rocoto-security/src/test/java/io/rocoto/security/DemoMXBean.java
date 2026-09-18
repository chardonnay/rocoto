package io.rocoto.security;

public interface DemoMXBean {

    int getLevel();

    void setLevel(int level);

    String getDbPassword();

    void setDbPassword(String password);

    Credentials getConnection();

    String greet(String name);

    void login(String user, String password);

    record Credentials(String url, String password) {
        @java.beans.ConstructorProperties({"url", "password"})
        public Credentials {
        }
    }
}
