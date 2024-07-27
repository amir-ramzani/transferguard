package io.github.nulled.transferguard.events;

import io.github.nulled.transferguard.common.ApiException;
import io.github.nulled.transferguard.features.FeatureRecorder;
import io.github.nulled.transferguard.features.RedisUnavailableException;
import org.springframework.stereotype.Service;

@Service
public class SecurityEventService {

    private final FeatureRecorder features;

    public SecurityEventService(FeatureRecorder features) {
        this.features = features;
    }

    public void record(SecurityEvent event) {
        if (event.type() == SecurityEventType.DEVICE_CHANGED && (event.deviceId() == null || event.deviceId().isBlank())) {
            throw ApiException.badRequest("deviceId", "is required when type is DEVICE_CHANGED");
        }
        try {
            features.recordSecurityEvent(event.account(), event.type().name(), event.deviceId(),
                    event.occurredAt().toInstant());
        } catch (RedisUnavailableException e) {
            throw ApiException.unavailable("The event store is unavailable; retry later.");
        }
    }
}
