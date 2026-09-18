package io.rocoto.api.stream;

import java.util.List;

import javax.management.ObjectName;

/**
 * What a client wants to be told about.
 *
 * @param attributes     attributes to poll; may be empty when only notifications are wanted
 * @param intervalMillis polling interval
 * @param onChange       only emit when the values differ from the previous emission
 * @param above          only emit while the (single, numeric) attribute is greater than this value
 * @param below          only emit while the (single, numeric) attribute is less than this value
 * @param notifications  also forward the MBean's JMX notifications
 */
public record SubscriptionSpec(ObjectName mbean, List<String> attributes, long intervalMillis, boolean onChange,
        Double above, Double below, boolean notifications) {
}
