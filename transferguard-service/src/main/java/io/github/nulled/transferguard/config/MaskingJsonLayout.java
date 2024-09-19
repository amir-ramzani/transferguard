package io.github.nulled.transferguard.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.LayoutBase;
import io.github.nulled.transferguard.common.Pii;
import java.time.Instant;
import java.util.Map;

/**
 * One JSON object per line with {@code traceId}/{@code spanId} from the MDC, and account numbers
 * masked ({@code 1402-****-3456}) in the message and stack trace, as PDPA requires.
 */
public class MaskingJsonLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString()).append(',');
        field(sb, "level", event.getLevel().toString()).append(',');
        field(sb, "logger", event.getLoggerName()).append(',');
        field(sb, "thread", event.getThreadName()).append(',');
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc.get("traceId") != null) {
            field(sb, "traceId", mdc.get("traceId")).append(',');
        }
        if (mdc.get("spanId") != null) {
            field(sb, "spanId", mdc.get("spanId")).append(',');
        }
        field(sb, "message", Pii.mask(event.getFormattedMessage()));
        IThrowableProxy tp = event.getThrowableProxy();
        if (tp != null) {
            sb.append(',');
            field(sb, "stack_trace", Pii.mask(ThrowableProxyUtil.asString(tp)));
        }
        sb.append('}').append(CoreConstants.LINE_SEPARATOR);
        return sb.toString();
    }

    private static StringBuilder field(StringBuilder sb, String name, String value) {
        sb.append('"').append(name).append("\":\"");
        escape(sb, value == null ? "" : value);
        return sb.append('"');
    }

    private static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
    }
}
