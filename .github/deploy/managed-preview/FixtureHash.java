import org.springframework.security.crypto.bcrypt.BCrypt;
/** Only synthetic offline test credentials; excluded from the runtime image/JAR. */
class FixtureHash {
    public static void main(String[] args) {
        System.out.print(BCrypt.hashpw("synthetic-preview-perimeter-only",BCrypt.gensalt(4)));
    }
}
