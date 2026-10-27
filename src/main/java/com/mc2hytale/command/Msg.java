package com.mc2hytale.command;

import com.hypixel.hytale.server.core.Message;

/** Chat messages with the MC2Hytale prefix. */
final class Msg {

    private static final String PREFIX = "[MC2Hytale] ";

    private Msg() {}

    static Message info(String text) {
        return of(text, "#FFFFFF");
    }

    static Message success(String text) {
        return of(text, "#55FF55");
    }

    static Message warn(String text) {
        return of(text, "#FFCC33");
    }

    static Message error(String text) {
        return of(text, "#FF5555");
    }

    /** An indented detail line without the prefix. */
    static Message line(String text, String color) {
        return Message.raw("  " + text).color(color);
    }

    private static Message of(String text, String color) {
        return Message.raw(PREFIX).color("#55AAFF").insert(Message.raw(text).color(color));
    }
}
