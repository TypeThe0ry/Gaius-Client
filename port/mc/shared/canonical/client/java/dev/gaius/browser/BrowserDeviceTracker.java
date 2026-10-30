package dev.gaius.browser;

import com.mojang.blaze3d.audio.DeviceList;
import com.mojang.blaze3d.audio.DeviceTracker;
import java.util.List;

/** Stable browser audio device view without a polling or callback thread. */
public final class BrowserDeviceTracker implements DeviceTracker {
    private static final DeviceList DEVICES =
            new DeviceList("Gaius Browser OpenAL", List.of("Gaius Browser OpenAL"));

    @Override
    public DeviceList currentDevices() {
        return DEVICES;
    }

    @Override
    public void tick() {
    }

    @Override
    public void forceRefresh() {
    }
}
