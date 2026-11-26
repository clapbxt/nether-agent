import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;

/**
 * Signs client.json for a release (Java 15+, no dependencies; run as a single file).
 *
 *   java tools/SignClient.java keygen
 *       prints a new key pair: the private key (hex) goes into the CLIENT_SIGNING_KEY secret of this repo, the
 *       public key (hex) into the launcher's crates/core/res/client-ed25519.pub
 *   java tools/SignClient.java sign build/client.json
 *       reads the private key (hex) from the CLIENT_SIGNING_KEY environment variable and writes
 *       build/client.json.sig: hex Ed25519 over "nether-client\n" followed by the file's exact bytes
 */
public final class SignClient {
    private static final byte[] PREFIX = "nether-client\n".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("keygen")) { keygen(); return; }
        if (args.length == 2 && args[0].equals("sign")) { sign(args[1]); return; }
        System.err.println("usage: SignClient keygen | sign <client.json>");
        System.exit(2);
    }

    private static void keygen() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] pub = kp.getPublic().getEncoded(), priv = kp.getPrivate().getEncoded();
        // X.509 and PKCS#8 encodings of Ed25519 both end with the raw 32-byte key
        System.out.println("private (CLIENT_SIGNING_KEY secret, keep it secret): " + hex(tail(priv)));
        System.out.println("public  (launcher crates/core/res/client-ed25519.pub): " + hex(tail(pub)));
    }

    private static void sign(String file) throws Exception {
        String key = System.getenv("CLIENT_SIGNING_KEY");
        if (key == null || key.trim().length() != 64) throw new IllegalStateException("CLIENT_SIGNING_KEY must be 64 hex digits");
        PrivateKey pk = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, unhex(key.trim())));
        byte[] body = Files.readAllBytes(Paths.get(file));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(pk);
        s.update(PREFIX);
        s.update(body);
        Files.write(Paths.get(file + ".sig"), hex(s.sign()).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tail(byte[] b) { byte[] t = new byte[32]; System.arraycopy(b, b.length - 32, t, 0, 32); return t; }

    private static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }

    private static byte[] unhex(String s) {
        byte[] raw = new BigInteger("10" + s, 16).toByteArray(); // leading marker keeps leading zero bytes
        byte[] out = new byte[s.length() / 2];
        System.arraycopy(raw, raw.length - out.length, out, 0, out.length);
        return out;
    }
}
