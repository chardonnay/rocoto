package io.rocoto.security;

public class Demo implements DemoMXBean {

    private int level = 1;
    private String dbPassword = "s3cr3t";

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public void setLevel(int level) {
        this.level = level;
    }

    @Override
    public String getDbPassword() {
        return dbPassword;
    }

    @Override
    public void setDbPassword(String password) {
        this.dbPassword = password;
    }

    @Override
    public Credentials getConnection() {
        return new Credentials("jdbc:h2:mem", "hunter2");
    }

    @Override
    public String greet(String name) {
        return "hello " + name;
    }

    @Override
    public void login(String user, String password) {
        // nothing
    }
}
