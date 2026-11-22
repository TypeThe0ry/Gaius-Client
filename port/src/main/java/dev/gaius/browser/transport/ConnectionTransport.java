package dev.gaius.browser.transport;

import io.netty.channel.Channel;

/** Configures one browser channel at the vanilla connection initializer. */
interface ConnectionTransport {
    void install(Channel channel, String address);
}
