package io.netty.channel.browser;

import io.netty.channel.DefaultEventLoop;

/** Single-JavaScript-turn Netty executor used only by browser WebSocket channels. */
final class BrowserInlineEventLoop extends DefaultEventLoop {
    @Override
    public boolean inEventLoop(Thread thread) {
        return true;
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
