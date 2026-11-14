import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Generates only the isolated, publicly known test account's verifier. */
class FixturePassword {
    public static void main(String[] args) {
        var encoder = new Argon2PasswordEncoder(16, 32, 1, 65_536, 3);
        System.out.print("{argon2id}" + encoder.encode("Synthetic-full-auth-fixture-2026!"));
    }
}
