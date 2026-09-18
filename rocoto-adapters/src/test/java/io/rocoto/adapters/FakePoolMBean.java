package io.rocoto.adapters;

public interface FakePoolMBean {
    int getcurrentThreadCount();

    int getcurrentThreadsBusy();

    int getmaxThreads();
}
