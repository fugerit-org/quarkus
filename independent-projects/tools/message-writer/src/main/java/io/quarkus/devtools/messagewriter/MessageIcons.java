package io.quarkus.devtools.messagewriter;

import io.smallrye.common.os.OS;

public enum MessageIcons {

    UP_TO_DATE_ICON(toEmoji("U+2714"), "v"),
    OUT_OF_DATE_ICON(toEmoji("U+26A0"), "x"),
    SUCCESS_ICON(toEmoji("U+2705"), "[SUCCESS]"),
    FAILURE_ICON(toEmoji("U+274C"), "[FAILURE]"),
    NOOP_ICON(toEmoji("U+1F44D"), ""),
    WARN_ICON(toEmoji("U+1F525"), "[WARN]"),
    ERROR_ICON(toEmoji("U+2757"), "[ERROR]");

    private String icon;
    private String messageCode;

    MessageIcons(String icon, String messageCode) {
        this.icon = icon;
        this.messageCode = messageCode;
    }

    // Simplify life so we can just understand what emojis we are using
    public static String toEmoji(String text) {
        String[] codes = text.replace("U+", "0x").split(" ");
        final StringBuilder stringBuilder = new StringBuilder();
        for (String code : codes) {
            final Integer intCode = Integer.decode(code.trim());
            for (Character character : Character.toChars(intCode)) {
                stringBuilder.append(character);
            }
        }
        return stringBuilder.toString();
    }

    public String iconOrMessage() {
        return OS.WINDOWS.isCurrent() ? messageCode : icon;
    }

    @Override
    public String toString() {
        return OS.WINDOWS.isCurrent() ? String.format("%s ", messageCode) : String.format("%s %s ", messageCode, icon);
    }
}
