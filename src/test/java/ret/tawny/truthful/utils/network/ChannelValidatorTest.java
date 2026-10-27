package ret.tawny.truthful.utils.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel validation regression tests: a modern, syntactically valid channel
 * longer than 32 characters must be accepted (the old hardcoded cutoff
 * rejected legal modded channels); actually malformed or oversized channels
 * must still be rejected per protocol version.
 */
class ChannelValidatorTest {

    private static final int MODERN = 770; // any 1.21+ protocol
    private static final int LEGACY = 340; // 1.12.2

    @Test
    void acceptsValidModernChannelAboveLegacyCutoffs() {
        // The exact class of channel the old 32-char limit used to reject.
        assertTrue(ChannelValidator.isValidChannel("example:legitimate_channel_with_a_long_name", MODERN));
        assertTrue(ChannelValidator.isValidChannel("somemod:some_channel_name_that_is_definitely_longer_than_32", MODERN));
        assertTrue(ChannelValidator.isValidChannel("minecraft:brand", MODERN));
        assertTrue(ChannelValidator.isValidChannel("fml", MODERN)); // valid identifier chars
    }

    @Test
    void acceptsMaxLengthModernChannel() {
        String channel = "a:" + "b".repeat(61); // 64 total
        assertTrue(ChannelValidator.isValidChannel(channel, MODERN));
    }

    @Test
    void rejectsOversizedModernChannel() {
        String channel = "a:" + "b".repeat(63); // 65 total, over the platform bound
        assertFalse(ChannelValidator.isValidChannel(channel, MODERN));
    }

    @Test
    void rejectsMalformedModernChannels() {
        assertFalse(ChannelValidator.isValidChannel("", MODERN));
        assertFalse(ChannelValidator.isValidChannel(null, MODERN));
        assertFalse(ChannelValidator.isValidChannel("example:", MODERN));      // empty path
        assertFalse(ChannelValidator.isValidChannel(":path", MODERN));          // empty namespace
        assertFalse(ChannelValidator.isValidChannel("a:b:c", MODERN));          // two colons
        assertFalse(ChannelValidator.isValidChannel("Example:Upper", MODERN));  // uppercase invalid
        assertFalse(ChannelValidator.isValidChannel("minecraft:has space", MODERN));
        assertFalse(ChannelValidator.isValidChannel("minecraft:bad!", MODERN));
    }

    @Test
    void acceptsLegacyChannelsWithinBound() {
        assertTrue(ChannelValidator.isValidChannel("MC|BEdit", LEGACY));
        assertTrue(ChannelValidator.isValidChannel("REGISTER", LEGACY));
        assertTrue(ChannelValidator.isValidChannel("FML|HS", LEGACY));
    }

    @Test
    void rejectsOversizedLegacyChannel() {
        assertFalse(ChannelValidator.isValidChannel("channel_name_that_is_way_too_long_for_1_12", LEGACY));
    }

    @Test
    void legacyBoundDoesNotRejectModernIdentifiers() {
        // A 40-char channel is fine on modern protocols and too long on legacy.
        String channel = "namespace:channel_name_exactly_forty_characters_x";
        assertTrue(ChannelValidator.isValidChannel(channel, MODERN));
        assertFalse(ChannelValidator.isValidChannel(channel, LEGACY));
    }
}
