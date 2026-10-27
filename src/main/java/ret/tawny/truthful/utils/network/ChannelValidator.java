package ret.tawny.truthful.utils.network;

/**
 * ChannelValidator - protocol-appropriate plugin-message channel validation.
 *
 * The wire format for custom payload channel names changed with 1.13 (protocol
 * 393): modern clients send namespaced identifiers, legacy clients sent short
 * opaque strings ("MC|BEdit", "REGISTER", "FML|HS"). Rejecting a modern,
 * syntactically valid channel just because it is longer than some hardcoded
 * number breaks legitimate modded/proxied clients - and the platform bound
 * that actually matters is the identifier validation the server itself
 * performs on registration. We mirror it:
 *
 *   modern (>= 393): optional "namespace:path" identifier. Namespace chars
 *     [a-z0-9_.-] (default "minecraft"), path chars [a-z0-9_.-/], both parts
 *     non-empty, total length capped at the platform bound (64, the standard
 *     channel-name bound the server enforces on registration).
 *   legacy (< 393): opaque string, at most 20 characters, any bytes.
 *
 * Payload size bounds live with the caller - they are a separate axis and
 * stay exactly as strict as before.
 */
public final class ChannelValidator {

    private ChannelValidator() {}

    /** 1.13, the release that turned channel names into identifiers. */
    public static final int MODERN_IDENTIFIER_PROTOCOL = 393;

    /** Platform bound for registered channel names (Messenger.MAX_CHANNEL_SIZE). */
    public static final int MAX_CHANNEL_LENGTH = 64;

    /** Legacy wire format reads the channel as a plain string of at most 20 chars. */
    public static final int MAX_LEGACY_CHANNEL_LENGTH = 20;

    public static boolean isValidChannel(String channel, int protocolVersion) {
        if (channel == null || channel.isEmpty()) return false;
        if (protocolVersion >= MODERN_IDENTIFIER_PROTOCOL) {
            return isValidIdentifier(channel);
        }
        return channel.length() <= MAX_LEGACY_CHANNEL_LENGTH;
    }

    /**
     * Namespaced-identifier check matching the server-side parse: an optional
     * "namespace:" prefix, then a path. Invalid characters or an empty part
     * make the channel unparseable on the wire, so such payloads are junk no
     * matter their length - length alone must never be the verdict.
     */
    static boolean isValidIdentifier(String channel) {
        if (channel.isEmpty() || channel.length() > MAX_CHANNEL_LENGTH) return false;

        final int split = channel.indexOf(':');
        final String namespace = split < 0 ? "minecraft" : channel.substring(0, split);
        final String path = split < 0 ? channel : channel.substring(split + 1);

        // At most one colon separator.
        if (split >= 0 && channel.indexOf(':', split + 1) >= 0) return false;
        if (namespace.isEmpty() || path.isEmpty()) return false;

        return charsValid(namespace, true) && charsValid(path, false);
    }

    private static boolean charsValid(String part, boolean namespace) {
        for (int i = 0; i < part.length(); i++) {
            final char c = part.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.'
                    || (!namespace && c == '/');
            if (!ok) return false;
        }
        return true;
    }
}
