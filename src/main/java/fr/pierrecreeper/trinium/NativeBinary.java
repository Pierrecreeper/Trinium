package fr.pierrecreeper.trinium;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded integrity/header checks. A matching hash is NOT a publisher signature. */
public final class NativeBinary {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private NativeBinary() { }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static byte[] readVerified(InputStream binary, InputStream digest, NativePlatform platform) throws IOException {
        if (binary == null || digest == null) throw new IOException("Missing library or SHA-256 digest");
        byte[] bytes = binary.readNBytes(MAX_BYTES + 1);
        byte[] digestBytes = digest.readNBytes(130);
        if (bytes.length > MAX_BYTES || digestBytes.length >= 130) throw new IOException("Native resource too large");
        String expected = new String(digestBytes, StandardCharsets.US_ASCII).trim();
        if (!expected.matches("[a-fA-F0-9]{64}") || !sha256(bytes).equalsIgnoreCase(expected))
            throw new IOException("Native SHA-256 digest mismatch");
        validateHeader(bytes, platform);
        return bytes;
    }
    /** Checks architecture/container only; loading and parity tests remain mandatory. */
    public static void validateHeader(byte[] bytes, NativePlatform platform) throws IOException {
        if (platform == null || NativePlatform.find(platform.id()) == null || bytes.length < 64 || bytes.length > MAX_BYTES)
            throw new IOException("Invalid native size/platform");
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean arm = platform.id().endsWith("aarch64");
        if (platform.id().startsWith("linux-")) {
            if (b.getInt(0) != 0x464c457f || b.get(4) != 2 || b.get(5) != 1
                    || Short.toUnsignedInt(b.getShort(16)) != 3
                    || Short.toUnsignedInt(b.getShort(18)) != (arm ? 183 : 62))
                throw new IOException("Invalid 64-bit ELF or wrong architecture");
        } else if (platform.id().startsWith("windows-")) {
            int pe = b.getInt(0x3c);
            if (b.getShort(0) != 0x5a4d || pe < 64 || pe > bytes.length - 24
                    || b.getInt(pe) != 0x00004550
                    || Short.toUnsignedInt(b.getShort(pe + 4)) != (arm ? 0xaa64 : 0x8664)
                    || (b.getShort(pe + 22) & 0x2000) == 0)
                throw new IOException("Invalid PE DLL or wrong architecture");
        } else if (platform.id().startsWith("macos-")) {
            if (b.getInt(0) != 0xfeedfacf || b.getInt(4) != (arm ? 0x0100000c : 0x01000007)
                    || b.getInt(12) != 6)
                throw new IOException("Invalid Mach-O dylib or wrong architecture");
        } else throw new IOException("Unknown native format");
    }
}
