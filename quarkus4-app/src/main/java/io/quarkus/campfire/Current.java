package io.quarkus.campfire;

import campfire.kit.Ctx;

/** The request's {@code Current} values ({@code Ctx.current}/{@code setCurrent}, whose reified Kotlin forms Java can't call). */
final class Current {
    private Current() {
    }

    static <T> T get(Ctx c, Class<T> type) {
        return type.cast(c.getCurrentValues().get(type));
    }

    static <T> void set(Ctx c, Class<T> type, T value) {
        c.getCurrentValues().put(type, value);
    }
}
