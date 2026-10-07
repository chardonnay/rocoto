package io.rocoto.metrics.push;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.metrics.format.StatsdFormat;
import io.rocoto.security.RequestPipeline;

/**
 * StatsD over UDP. Lines are packed into datagrams of at most {@code maxPacketBytes} (default 1432,
 * safe for an Ethernet MTU). Counters are sent as increments since the previous push.
 */
final class StatsdPusher extends MetricsPusher {

    private final String host;
    private final int port;
    private final int maxPacketBytes;
    private final StatsdFormat format;

    StatsdPusher(Config c, MetricsCollector collector, RequestPipeline pipeline, Supplier<List<MetricFamily>> extra) {
        super("statsd", c, 10, collector, pipeline, extra);
        this.host = c.string("host", "127.0.0.1");
        this.port = c.integer("port", 8125);
        this.maxPacketBytes = Math.max(256, Math.min(65_000, c.integer("maxPacketBytes", 1432)));
        this.format = new StatsdFormat(c.string("prefix", "rocoto"), c.bool("tags", false), true);
        if (port <= 0 || port > 65535) {
            throw new ConfigException("metrics.push.statsd.port is invalid");
        }
    }

    @Override
    protected String target() {
        return host + ":" + port + " (UDP)";
    }

    @Override
    protected void push(List<MetricFamily> families, long nowMillis) throws Exception {
        List<String> lines = format.lines(families);
        InetAddress address = InetAddress.getByName(host);
        try (DatagramSocket socket = new DatagramSocket()) {
            StringBuilder packet = new StringBuilder(maxPacketBytes);
            for (String line : lines) {
                int size = line.getBytes(StandardCharsets.UTF_8).length;
                if (size > maxPacketBytes) {
                    continue; // a single line that does not fit would be truncated by the receiver
                }
                int current = packet.toString().getBytes(StandardCharsets.UTF_8).length;
                if (current > 0 && current + 1 + size > maxPacketBytes) {
                    send(socket, address, packet);
                    packet.setLength(0);
                }
                if (packet.length() > 0) {
                    packet.append('\n');
                }
                packet.append(line);
            }
            if (packet.length() > 0) {
                send(socket, address, packet);
            }
        }
    }

    private void send(DatagramSocket socket, InetAddress address, CharSequence packet) throws java.io.IOException {
        byte[] data = packet.toString().getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(data, data.length, address, port));
    }
}
