package io.rocoto.core.testbeans;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface SampleMXBean {

    long getMaxLong();

    long getSmallLong();

    BigInteger getHuge();

    byte[] getBytes();

    Set<String> getTags();

    Map<String, Integer> getCounts();

    List<Point> getPoints();

    double getNotANumber();

    String getName();

    void setName(String name);

    int getLevel();

    void setLevel(int level);

    Point getOrigin();

    void setOrigin(Point origin);

    String getDbPassword();

    int add(int a, int b);

    String add(String a, String b);

    long sum(long[] values);

    String overloaded(int a);

    String overloaded(String a);

    Point shift(Point p, int dx);

    void fail();

    record Point(int x, int y) {
        @java.beans.ConstructorProperties({"x", "y"})
        public Point {
        }
    }
}
