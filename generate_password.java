import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class generate_password {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String password = "protrader";
        String encoded = encoder.encode(password);
        System.out.println("BCrypt hash for 'protrader': " + encoded);
    }
}
