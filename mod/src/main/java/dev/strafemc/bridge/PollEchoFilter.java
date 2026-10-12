package dev.strafemc.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides the net console lines that answer strafeMC's own background polling (watched settings, status), so a console
 * only shows replies to what was typed into it. One per console connection (it tracks an open status block).
 */
public final class PollEchoFilter {
	private static final Pattern VALUE_LINE = Pattern.compile("^([a-z0-9_]+) = ");
	private static final Pattern UNKNOWN_LINE = Pattern.compile("^Unknown command '([a-z0-9_]+)'");
	private volatile String asked = "";
	private boolean inStatus;

	/** Remember what was typed (its own reply is always shown). */
	public void typed(String command) {
		asked = command.trim().split("\s+")[0].toLowerCase();
	}

	/** True for a line that answers the background polling rather than the typed command. */
	public boolean drop(String line) {
		String t = line.trim();
		if (t.startsWith("Server:  ")) inStatus = !asked.equals("status");
		if (inStatus) {
			if (t.equals("#end")) inStatus = false;
			return true;
		}
		Matcher u = UNKNOWN_LINE.matcher(t);
		if (u.find()) return NetconPoll.WATCHED.contains(u.group(1)) && !u.group(1).equals(asked);
		Matcher m = VALUE_LINE.matcher(t);
		return m.find() && NetconPoll.WATCHED.contains(m.group(1)) && !m.group(1).equals(asked);
	}
}
