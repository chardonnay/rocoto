package io.rocoto.adapters;

public class FakePool implements FakePoolMBean {
    @Override
    public int getcurrentThreadCount() {
        return 10;
    }

    @Override
    public int getcurrentThreadsBusy() {
        return 3;
    }

    @Override
    public int getmaxThreads() {
        return 200;
    }
}
