import org.jasypt.util.text.BasicTextEncryptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class SecurePasswordUtil {
    
    // Método auxiliar para repetir strings (compatible con Java 8)
    private static String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder(str.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
    
    private static String getMasterKey() {
        String key = System.getenv("UPDATER_MASTER_KEY");
        if (key == null || key.isEmpty()) {
            throw new RuntimeException("ERROR: Variable de entorno UPDATER_MASTER_KEY no configurada");
        }
        return key;
    }
    
    public static String encryptPassword(String plainPassword) {
        BasicTextEncryptor encryptor = new BasicTextEncryptor();
        encryptor.setPassword(getMasterKey());
        return encryptor.encrypt(plainPassword);
    }
    
    public static String decryptPassword(String encryptedPassword) {
        try {
            BasicTextEncryptor encryptor = new BasicTextEncryptor();
            encryptor.setPassword(getMasterKey());
            return encryptor.decrypt(encryptedPassword);
        } catch (Exception e) {
            throw new RuntimeException("Error al descifrar contraseña. Verifica UPDATER_MASTER_KEY", e);
        }
    }
    
    public static String readAndDecryptFromFile(String filePath) throws IOException {
        String encryptedPassword = new String(
            Files.readAllBytes(Paths.get(filePath)), 
            StandardCharsets.UTF_8
        ).trim();
        return decryptPassword(encryptedPassword);
    }
    
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Uso:");
            System.out.println("  java SecurePasswordUtil encrypt <contraseña_a_cifrar>");
            System.out.println("  java SecurePasswordUtil decrypt <contraseña_cifrada>");
            System.out.println("Asegurate de configurar UPDATER_MASTER_KEY como variable de entorno");
            System.exit(1);
        }

        String command = args[0].toLowerCase();
        String value = args[1];

        String separator = repeat("=", 60);

        try {
            if ("encrypt".equals(command)) {
                String encrypted = encryptPassword(value);
                System.out.println(separator);
                System.out.println("CONTRASENA CIFRADA GENERADA:");
                System.out.println(separator);
                System.out.println(encrypted);
                System.out.println(separator);
                System.out.println("\nGuarda este valor en la variable de entorno FTP_PASSWORD_ENCRYPTED");
            } else if ("decrypt".equals(command)) {
                String decrypted = decryptPassword(value);
                System.out.println(separator);
                System.out.println("CONTRASENA DESCIFRADA:");
                System.out.println(separator);
                System.out.println(decrypted);
                System.out.println(separator);
            } else {
                System.err.println("Comando inválido. Usa 'encrypt' o 'decrypt'.");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error durante la operación: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
