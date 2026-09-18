package io.rocoto.core.testbeans;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Sample implements SampleMXBean {

    private String name = "rocoto";
    private int level = 1;
    private Point origin = new Point(0, 0);

    @Override
    public long getMaxLong() {
        return Long.MAX_VALUE;
    }

    @Override
    public long getSmallLong() {
        return 42L;
    }

    @Override
    public BigInteger getHuge() {
        return new BigInteger("123456789012345678901234567890");
    }

    @Override
    public byte[] getBytes() {
        return new byte[] {1, 2, 3, (byte) 0xff};
    }

    @Override
    public Set<String> getTags() {
        return new LinkedHashSet<>(List.of("a", "b"));
    }

    @Override
    public Map<String, Integer> getCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("one", 1);
        m.put("two", 2);
        return m;
    }

    @Override
    public List<Point> getPoints() {
        return List.of(new Point(1, 2), new Point(3, 4));
    }

    @Override
    public double getNotANumber() {
        return Double.NaN;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public void setLevel(int level) {
        this.level = level;
    }

    @Override
    public Point getOrigin() {
        return origin;
    }

    @Override
    public void setOrigin(Point origin) {
        this.origin = origin;
    }

    @Override
    public String getDbPassword() {
        return "s3cr3t";
    }

    @Override
    public int add(int a, int b) {
        return a + b;
    }

    @Override
    public String add(String a, String b) {
        return a + b;
    }

    @Override
    public long sum(long[] values) {
        long s = 0;
        for (long v : values) {
            s += v;
        }
        return s;
    }

    @Override
    public String overloaded(int a) {
        return "int:" + a;
    }

    @Override
    public String overloaded(String a) {
        return "string:" + a;
    }

    @Override
    public Point shift(Point p, int dx) {
        return new Point(p.x() + dx, p.y());
    }

    @Override
    public void fail() {
        throw new IllegalStateException("boom");
    }
}
