package com.example.parkour;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;

final class ParkourText {
	static final int SHADOW = 0x231B32;
	static final int MUTED = 0x86B4BB;
	static final int TEXT = 0xF5FEFF;
	static final int SOFT_TEXT = 0xDBE9F0;
	static final int GOLD = 0xFFD94D;
	static final int SILVER = 0xB1C5D8;
	static final int BRONZE = 0xAD795C;
	static final int ORANGE = 0xF26E26;
	static final int CYAN = 0x4DEAE9;
	static final int GREEN = 0x90F99E;
	static final int RED = 0xD31212;
	static final int PINK = 0xF773C0;

	static final String SHADOW_HEX = "#231b32";
	static final String MUTED_HEX = "#86b4bb";
	static final String TEXT_HEX = "#f5feff";
	static final String SOFT_TEXT_HEX = "#dbe9f0";
	static final String GOLD_HEX = "#ffd94d";
	static final String SILVER_HEX = "#b1c5d8";
	static final String BRONZE_HEX = "#ad795c";
	static final String CYAN_HEX = "#4deae9";
	static final String GREEN_HEX = "#90f99e";
	static final String RED_HEX = "#d31212";
	static final String PINK_HEX = "#f773c0";

	private ParkourText() {
	}

	static MutableComponent timerActionBar(long elapsedMillis) {
		return literal(formatTime(elapsedMillis), SOFT_TEXT).withStyle(ChatFormatting.BOLD);
	}

	static MutableComponent finishActionBar(long currentMillis, long personalBestMillis, long worldRecordMillis) {
		return label("Tijd: ", GOLD)
			.append(literal(formatTime(currentMillis), SOFT_TEXT).withStyle(ChatFormatting.BOLD))
			.append(literal("  |  ", MUTED))
			.append(label("PB: ", GOLD))
			.append(literal(formatTime(personalBestMillis), CYAN).withStyle(ChatFormatting.BOLD))
			.append(literal("  |  ", MUTED))
			.append(label("WR: ", GOLD))
			.append(literal(formatTime(worldRecordMillis), GREEN).withStyle(ChatFormatting.BOLD));
	}

	static MutableComponent stoppedActionBar() {
		return label("Parkour gestopt", RED).withStyle(ChatFormatting.BOLD);
	}

	static MutableComponent recordBroadcast(String playerName, String parkourName, long millis) {
		return label("Nieuw record! ", GOLD)
			.withStyle(ChatFormatting.BOLD)
			.append(literal(playerName, PINK).withStyle(ChatFormatting.BOLD))
			.append(literal(" heeft ", MUTED))
			.append(literal(parkourName, CYAN))
			.append(literal(" voltooid in ", MUTED))
			.append(literal(formatTime(millis), GREEN).withStyle(ChatFormatting.BOLD));
	}

	static MutableComponent literal(String text, int color) {
		return Component.literal(smallCaps(text)).withStyle(style -> style.withColor(TextColor.fromRgb(color)));
	}

	static MutableComponent raw(String text, int color) {
		return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(color)));
	}

	static MutableComponent label(String text, int color) {
		return literal(text, color);
	}

	static String smallCaps(String value) {
		StringBuilder result = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			result.append(smallCaps(value.charAt(i)));
		}
		return result.toString();
	}

	private static char smallCaps(char character) {
		return switch (Character.toLowerCase(character)) {
			case 'a' -> 'ᴀ';
			case 'b' -> 'ʙ';
			case 'c' -> 'ᴄ';
			case 'd' -> 'ᴅ';
			case 'e' -> 'ᴇ';
			case 'f' -> 'ꜰ';
			case 'g' -> 'ɢ';
			case 'h' -> 'ʜ';
			case 'i' -> 'ɪ';
			case 'j' -> 'ᴊ';
			case 'k' -> 'ᴋ';
			case 'l' -> 'ʟ';
			case 'm' -> 'ᴍ';
			case 'n' -> 'ɴ';
			case 'o' -> 'ᴏ';
			case 'p' -> 'ᴘ';
			case 'r' -> 'ʀ';
			case 's' -> 'ѕ';
			case 't' -> 'ᴛ';
			case 'u' -> 'ᴜ';
			case 'v' -> 'ᴠ';
			case 'w' -> 'ᴡ';
			case 'x' -> 'х';
			case 'y' -> 'ʏ';
			case 'z' -> 'ᴢ';
			default -> character;
		};
	}

	static String formatTime(long millis) {
		long minutes = millis / 60000L;
		long seconds = (millis % 60000L) / 1000L;
		long milliseconds = millis % 1000L;
		return "%02d:%02d.%03d".formatted(minutes, seconds, milliseconds);
	}

	static String escapeSnbtString(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
