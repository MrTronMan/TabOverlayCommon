/*
 *     Copyright (C) 2020 Florian Stober
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package de.codecrafter47.taboverlay.config.placeholder;

import de.codecrafter47.data.api.TypeToken;
import de.codecrafter47.taboverlay.config.ConfigTabOverlayManager;
import de.codecrafter47.taboverlay.config.context.Context;
import de.codecrafter47.taboverlay.config.template.TemplateCreationContext;

import javax.annotation.Nonnull;
import java.text.SimpleDateFormat;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TimePlaceholderResolver implements PlaceholderResolver<Context> {

    private static final SimpleDateFormat DEFAULT_FORMAT = new SimpleDateFormat("HH:mm:ss");
    // todo replace with type token from data api once available
    private static final TypeToken<Long> TYPE_TOKEN_LONG = TypeToken.create();
    private final ConfigTabOverlayManager configTabOverlayManager;

    public TimePlaceholderResolver(ConfigTabOverlayManager configTabOverlayManager) {
        this.configTabOverlayManager = configTabOverlayManager;
    }

    @Nonnull
    @Override
    public PlaceholderBuilder<?, ?> resolve(PlaceholderBuilder<Context, ?> builder, List<PlaceholderArg> args, TemplateCreationContext tcc) throws UnknownPlaceholderException, PlaceholderException {
        if (args.size() >= 1 && args.get(0) instanceof PlaceholderArg.Text && "time".equalsIgnoreCase(((PlaceholderArg.Text) args.get(0)).getValue())) {
            if (args.size() >= 2 && args.get(1) instanceof PlaceholderArg.Text
                    && "dayoffset".equalsIgnoreCase(((PlaceholderArg.Text) args.get(1)).getValue())) {
                return resolveDayOffset(builder, args);
            }
            SimpleDateFormat format = DEFAULT_FORMAT;
            if (args.size() > 1) {
                StringBuilder formatString = new StringBuilder();
                TimeZone timeZone = configTabOverlayManager.getTimeZone();
                for (int i = 1; i < args.size(); i++) {
                    if (args.get(i) instanceof PlaceholderArg.Text) {
                        String s = ((PlaceholderArg.Text) args.get(i)).getValue();
                        if (s.regionMatches(true, 0, "tz=", 0, 3)) {
                            String id = s.substring(3);
                            try {
                                timeZone = TimeZone.getTimeZone(ZoneId.of(id, ZoneId.SHORT_IDS));
                            } catch (DateTimeException ex) {
                                throw new PlaceholderException("Unknown time zone " + id, ex);
                            }
                            continue;
                        }
                        if (formatString.length() > 0) {
                            formatString.append(' ');
                        }
                        formatString.append(s);
                    } else {
                        throw new PlaceholderException("Use of placeholder in time format is not allowed");
                    }
                }
                try {
                    format = new SimpleDateFormat(formatString.length() > 0 ? formatString.toString() : "HH:mm:ss");
                    if (timeZone != null) {
                        format.setTimeZone(timeZone);
                    }
                } catch (IllegalArgumentException ex) {
                    throw new PlaceholderException("Invalid time format", ex);
                }
            }
            args.clear();
            return builder.acquireData(TimeProvider::new, TYPE_TOKEN_LONG, false)
                    .transformData(format::format, TypeToken.STRING);
        }
        throw new UnknownPlaceholderException();
    }

    private PlaceholderBuilder<?, ?> resolveDayOffset(PlaceholderBuilder<Context, ?> builder, List<PlaceholderArg> args) throws PlaceholderException {
        ZoneId target = null;
        TimeZone global = configTabOverlayManager.getTimeZone();
        final ZoneId reference = global != null ? global.toZoneId() : ZoneId.systemDefault();
        for (int i = 2; i < args.size(); i++) {
            if (!(args.get(i) instanceof PlaceholderArg.Text)) {
                throw new PlaceholderException("Use of placeholder in time dayoffset is not allowed");
            }
            String s = ((PlaceholderArg.Text) args.get(i)).getValue();
            if (s.regionMatches(true, 0, "tz=", 0, 3)) {
                try {
                    target = ZoneId.of(s.substring(3), ZoneId.SHORT_IDS);
                } catch (DateTimeException ex) {
                    throw new PlaceholderException("Unknown time zone " + s.substring(3), ex);
                }
            } else {
                throw new PlaceholderException("Unknown argument " + s);
            }
        }
        if (target == null) {
            throw new PlaceholderException("time dayoffset requires tz=<zone>");
        }
        final ZoneId targetZone = target;
        args.clear();
        return builder.acquireData(TimeProvider::new, TYPE_TOKEN_LONG, false)
                .transformData(millis -> {
                    Instant now = Instant.ofEpochMilli(millis);
                    long diff = ChronoUnit.DAYS.between(
                            now.atZone(reference).toLocalDate(),
                            now.atZone(targetZone).toLocalDate());
                    if (diff == 0) {
                        return "";
                    }
                    return diff > 0 ? "(+" + diff + ")" : "(" + diff + ")";
                }, TypeToken.STRING);
    }

    private static class TimeProvider implements PlaceholderDataProvider<Context, Long> {

        private ScheduledFuture<?> future;

        @Override
        public void activate(Context context, Runnable listener) {
            // todo can do better if seconds not used?
            future = context.getTabEventQueue().scheduleWithFixedDelay(listener, 1, 1, TimeUnit.SECONDS);
        }

        @Override
        public void deactivate() {
            future.cancel(false);
        }

        @Override
        public Long getData() {
            return System.currentTimeMillis();
        }
    }
}
