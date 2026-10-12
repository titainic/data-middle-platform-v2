import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class PasswordHash {
    public static void main(String[] args) throws Exception {
        String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must be provided on standard input");
        }
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }
}
