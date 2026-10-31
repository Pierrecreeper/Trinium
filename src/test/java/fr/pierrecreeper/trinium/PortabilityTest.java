package fr.pierrecreeper.trinium;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Tests use synthetic HEADERS only, never claim to execute Windows/macOS/Rust binaries. */
public final class PortabilityTest {
    private static int checks;
    private PortabilityTest() { }
    private static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    private static void rejected(IOAction action, String why) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + why); }
        catch (IOException expected) { checks++; }
    }
    @FunctionalInterface private interface IOAction { void run() throws Exception; }
    private static byte[] header(NativePlatform p) {
        byte[] bytes = new byte[256];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        boolean arm = p.id().endsWith("aarch64");
        if (p.id().startsWith("linux-")) {
            b.putInt(0,0x464c457f); b.put(4,(byte)2); b.put(5,(byte)1);
            b.putShort(16,(short)3); b.putShort(18,(short)(arm?183:62));
        } else if (p.id().startsWith("windows-")) {
            b.putShort(0,(short)0x5a4d); b.putInt(0x3c,128); b.putInt(128,0x00004550);
            b.putShort(132,(short)(arm?0xaa64:0x8664)); b.putShort(150,(short)0x2000);
        } else {
            b.putInt(0,0xfeedfacf); b.putInt(4,arm?0x0100000c:0x01000007); b.putInt(12,6);
        }
        return bytes;
    }
    public static void run() throws Exception {
        String[][] cases = {
            {"Windows 10","amd64","windows-x86_64"}, {"Windows 11","arm64","windows-aarch64"},
            {"Windows Server 2022","x86_64","windows-x86_64"}, {"Mac OS X","x86_64","macos-x86_64"},
            {"Mac OS X","aarch64","macos-aarch64"}, {"Darwin","arm64","macos-aarch64"},
            {"Linux","amd64","linux-x86_64"}, {"Linux","aarch64","linux-aarch64"},
            {" linux "," X64 ","linux-x86_64"}, {"macOS","arm64","macos-aarch64"},
            {"FreeBSD","amd64",null}, {"Linux","i386",null}, {"Linux","riscv64",null},
            {"Windows 11","x86",null}, {"Cygwin","amd64",null}, {"NotWindows","x64",null},
            {"Solaris","x86_64",null}, {null,null,null}, {"Linux",null,null}, {null,"arm64",null}
        };
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for (String[] c : cases) {
                NativePlatform p = NativePlatform.detect(c[0],c[1]);
                check(Objects.equals(c[2], p == null ? null : p.id()), "detection: " + Arrays.toString(c));
            }
        } finally { Locale.setDefault(old); }
        check(NativePlatform.supported().size()==6,"six packaging targets");
        for (NativePlatform p : NativePlatform.supported()) {
            byte[] bytes=header(p);
            byte[] digest=(NativeBinary.sha256(bytes)+"\n").getBytes(StandardCharsets.US_ASCII);
            check(Arrays.equals(bytes,NativeBinary.readVerified(new ByteArrayInputStream(bytes),new ByteArrayInputStream(digest),p)),"valid header/hash " + p.id());
            byte[] tampered=bytes.clone();tampered[255]^=1;
            rejected(()->NativeBinary.readVerified(new ByteArrayInputStream(tampered),new ByteArrayInputStream(digest),p),"tampered bytes");
            rejected(()->NativeBinary.readVerified(new ByteArrayInputStream(bytes),null,p),"missing hash");
            rejected(()->NativeBinary.validateHeader(new byte[3],p),"truncated binary");
            for (NativePlatform other : NativePlatform.supported()) if (!p.equals(other))
                rejected(()->NativeBinary.validateHeader(bytes,other),"wrong platform/architecture");
        }
        for (int pe : new int[]{-1,0,63,233,Integer.MAX_VALUE}) {
            NativePlatform p=NativePlatform.find("windows-x86_64");byte[] bytes=header(p);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c,pe);
            rejected(()->NativeBinary.validateHeader(bytes,p),"PE offset bounds");
        }
        NativePlatform linux=NativePlatform.find("linux-x86_64");
        byte[] tooBig=new byte[NativeBinary.MAX_BYTES+1];
        rejected(()->NativeBinary.readVerified(new ByteArrayInputStream(tooBig),new ByteArrayInputStream("0".repeat(64).getBytes(StandardCharsets.US_ASCII)),linux),"size bound");
        byte[] bytes=header(linux);
        rejected(()->NativeBinary.readVerified(new ByteArrayInputStream(bytes),new ByteArrayInputStream("0".repeat(130).getBytes(StandardCharsets.US_ASCII)),linux),"oversized hash");
        inventory();
        System.out.println("PASS portability: " + checks + " checks (OS/arch names, synthetic headers, hashes, bounds); no foreign native execution");
    }
    private static void inventory() throws Exception {
        Map<String, byte[]> resources = new HashMap<>();
        for (NativePlatform platform : NativePlatform.supported()) {
            byte[] bytes = header(platform);
            resources.put(platform.resource(), bytes);
            resources.put(platform.resource() + ".sha256", NativeBinary.sha256(bytes).getBytes(StandardCharsets.US_ASCII));
            resources.put("/natives/" + platform.id() + "/build.properties",
                    ("version=" + Trinium.VERSION + "\nabi=3\ntarget=" + platform.target() + "\n").getBytes(StandardCharsets.UTF_8));
        }
        NativeInventory.Resources source = name -> {
            byte[] value = resources.get(name);
            return value == null ? null : new ByteArrayInputStream(value);
        };
        check(NativeInventory.verifyAll(source) == 6, "ABI3 six-platform header inventory");
        NativePlatform hostFixture = NativePlatform.find("linux-x86_64");
        String metadataPath = "/natives/" + hostFixture.id() + "/build.properties";
        byte[] validMetadata = resources.get(metadataPath);
        for (String invalid : List.of(
                "version=" + Trinium.VERSION + "\nabi=1\ntarget=" + hostFixture.target() + "\n",
                "version=outdated\nabi=3\ntarget=" + hostFixture.target() + "\n",
                "version=" + Trinium.VERSION + "\nabi=3\ntarget=wrong\n",
                "#".repeat(16 * 1024 + 1))) {
            resources.put(metadataPath, invalid.getBytes(StandardCharsets.UTF_8));
            rejected(() -> NativeInventory.verifyAll(source), "invalid or oversized native inventory metadata");
        }
        resources.remove(metadataPath);
        rejected(() -> NativeInventory.verifyAll(source), "missing inventory metadata");
        resources.put(metadataPath, validMetadata);
        resources.remove(hostFixture.resource());
        rejected(() -> NativeInventory.verifyAll(source), "missing inventory binary");
    }
    public static void main(String[] args) throws Exception { run(); }
}
