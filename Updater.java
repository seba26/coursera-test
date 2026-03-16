import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

/**
 * Updater FTP Incremental v2.4
 *
 * Cambios principales sobre v2.3:
 * - Actualizaciones de UI por EDT usando publish/process.
 * - Manejo de password con char[] y limpieza real en memoria.
 * - Mayor robustez en checksum remoto (validación de completePendingCommand).
 * - Defaults más portables para jar (PATH/JAVA_HOME).
 */
public class Updater {

    private static String LOGO_PATH = "C:\\deploy\\Updater\\logo.png";
    private static final int LOGO_MAX_W = 360;
    private static final int LOGO_MAX_H = 120;
    private static final Color BRAND = new Color(45, 156, 219);

    public static void main(String[] args) {
        final String ftpHost = args.length > 0 ? args[0] : "ftp.miservidor.com";
        final String ftpUser = args.length > 1 ? args[1] : "usuario";

        final char[] ftpPass;
        final String ftpRemoteDir;
        final int ftpPort;
        final String localExploded;
        final String outJar;
        final String jarExe;
        final String mainJar;

        if (args.length > 2 && "@env".equals(args[2])) {
            String envVarName = args.length > 3 ? args[3] : "FTP_PASSWORD_ENCRYPTED";
            String encryptedPass = System.getenv(envVarName);
            if (encryptedPass == null || encryptedPass.isEmpty()) {
                throw new RuntimeException("Variable de entorno " + envVarName + " no configurada");
            }
            try {
                String decrypted = SecurePasswordUtil.decryptPassword(encryptedPass);
                ftpPass = decrypted.toCharArray();
                // limpiar copia temporal inmutable lo antes posible
                decrypted = null;
            } catch (Exception e) {
                throw new RuntimeException("Error al descifrar. Verifica UPDATER_MASTER_KEY", e);
            }
            ftpRemoteDir = args.length > 4 ? args[4] : "/myapp_exploded";
            ftpPort = args.length > 5 ? Integer.parseInt(args[5]) : 21;
            localExploded = args.length > 6 ? args[6] : "C:\\deploy\\Subagencia\\exploded";
            outJar = args.length > 7 ? args[7] : "C:\\deploy\\Subagencia\\SUBAGENCIA_REDPAGOS_GXWS.jar";
            jarExe = args.length > 8 ? args[8] : resolveJarCommand();
            mainJar = args.length > 9 ? args[9] : outJar;
        } else if (args.length > 2 && "@file".equals(args[2])) {
            String passFile = args.length > 3 ? args[3] : "ftp_pass.txt";
            try {
                String decrypted = SecurePasswordUtil.readAndDecryptFromFile(passFile);
                ftpPass = decrypted.toCharArray();
                decrypted = null;
            } catch (Exception e) {
                throw new RuntimeException("No se pudo leer/descifrar archivo de password: " + passFile, e);
            }
            ftpRemoteDir = args.length > 4 ? args[4] : "/myapp_exploded";
            ftpPort = args.length > 5 ? Integer.parseInt(args[5]) : 21;
            localExploded = args.length > 6 ? args[6] : "C:\\deploy\\Subagencia\\exploded";
            outJar = args.length > 7 ? args[7] : "C:\\deploy\\Subagencia\\SUBAGENCIA_REDPAGOS_GXWS.jar";
            jarExe = args.length > 8 ? args[8] : resolveJarCommand();
            mainJar = args.length > 9 ? args[9] : outJar;
        } else {
            // Compatibilidad: idealmente deshabilitar este modo en producción.
            ftpPass = (args.length > 2 ? args[2] : "password").toCharArray();
            ftpRemoteDir = args.length > 3 ? args[3] : "/myapp_exploded";
            ftpPort = args.length > 4 ? Integer.parseInt(args[4]) : 21;
            localExploded = args.length > 5 ? args[5] : "C:\\deploy\\Subagencia\\exploded";
            outJar = args.length > 6 ? args[6] : "C:\\deploy\\Subagencia\\SUBAGENCIA_REDPAGOS_GXWS.jar";
            jarExe = args.length > 7 ? args[7] : resolveJarCommand();
            mainJar = args.length > 8 ? args[8] : outJar;
        }

        if (args.length > 10) LOGO_PATH = args[10];

        JFrame f = new JFrame("Actualizando...");
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JLabel logoLabel = buildLogo(LOGO_PATH, LOGO_MAX_W);
        JComponent loader = buildModernLoader();

        ModernIndeterminateBar bar = new ModernIndeterminateBar(6, 1000);
        bar.setForeground(BRAND);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        JLabel lblStep = new JLabel("Preparando...", SwingConstants.LEFT);
        JLabel lblTime = new JLabel("00:00", SwingConstants.RIGHT);
        JPanel status = new JPanel(new BorderLayout());
        status.add(lblStep, BorderLayout.WEST);
        status.add(lblTime, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(logoLabel, BorderLayout.NORTH);
        north.add(loader, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(north, BorderLayout.NORTH);
        root.add(bar, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);

        f.setContentPane(root);
        f.pack();
        f.setResizable(false);
        f.setLocationRelativeTo(null);
        f.setVisible(true);

        final long t0 = System.currentTimeMillis();
        new javax.swing.Timer(500, e -> {
            long secs = (System.currentTimeMillis() - t0) / 1000;
            lblTime.setText(String.format("%02d:%02d", secs / 60, secs % 60));
        }).start();

        new SwingWorker<Void, String>() {
            private final Path logPath = Paths.get(localExploded, "update.log");
            private BufferedWriter logWriter;
            private int filesDownloaded, filesSkipped, filesDeleted, filesExtracted, filesChecksumVerified;
            private final Set<String> remoteFilesPaths = new HashSet<>();
            private final List<String> changedFiles = new ArrayList<>();

            private void log(String s) {
                try {
                    if (logWriter != null) {
                        logWriter.write(s);
                        logWriter.newLine();
                        logWriter.flush();
                    }
                } catch (IOException ignored) {
                }
            }

            private void setStep(String step) {
                publish(step);
                log("[STEP] " + step);
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    lblStep.setText(chunks.get(chunks.size() - 1));
                }
            }

            private int runAndStream(List<String> cmd, File workDir) throws Exception {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                if (workDir != null) pb.directory(workDir);
                pb.redirectErrorStream(true);
                Process pr = pb.start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String lower = line.toLowerCase(Locale.ROOT);
                        if (lower.contains("error") || lower.contains("warning")) {
                            log(line);
                        }
                    }
                }
                return pr.waitFor();
            }

            private void extractJarIfNeeded(String jarPath, String explodedDir) throws Exception {
                File jarFile = new File(jarPath);
                File exploded = new File(explodedDir);
                if (!jarFile.exists()) {
                    log("INFO: No existe JAR previo");
                    return;
                }

                boolean needsExtraction = !exploded.exists();
                if (!needsExtraction) {
                    File[] files = exploded.listFiles((dir, name) -> !"update.log".equals(name));
                    needsExtraction = files == null || files.length == 0;
                }
                if (!needsExtraction) {
                    log("INFO: Base local ya existe");
                    return;
                }

                setStep("Extrayendo JAR base...");
                exploded.mkdirs();

                try (JarFile jar = new JarFile(jarFile)) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        File dest = new File(exploded, entry.getName());
                        if (entry.isDirectory()) {
                            dest.mkdirs();
                            continue;
                        }
                        File parent = dest.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (InputStream is = jar.getInputStream(entry);
                             OutputStream os = new BufferedOutputStream(new FileOutputStream(dest), 65536)) {
                            byte[] buffer = new byte[65536];
                            int bytesRead;
                            while ((bytesRead = is.read(buffer)) != -1) os.write(buffer, 0, bytesRead);
                        }
                        long entryTime = entry.getTime();
                        if (entryTime > 0) {
                            dest.setLastModified(entryTime);
                        }
                        filesExtracted++;
                    }
                }
                log("Extraídos: " + filesExtracted + " archivos");
            }

            private byte[] calculateLocalChecksum(File file) throws Exception {
                MessageDigest md = MessageDigest.getInstance("MD5");
                try (InputStream fis = new FileInputStream(file);
                     DigestInputStream dis = new DigestInputStream(fis, md)) {
                    byte[] buffer = new byte[8192];
                    while (dis.read(buffer) != -1) {
                    }
                }
                return md.digest();
            }

            private byte[] calculateRemoteChecksum(FTPClient ftp, String remoteFilePath) throws Exception {
                MessageDigest md = MessageDigest.getInstance("MD5");
                InputStream remoteStream = ftp.retrieveFileStream(remoteFilePath);
                if (remoteStream == null) return null;

                try (DigestInputStream dis = new DigestInputStream(remoteStream, md)) {
                    byte[] buffer = new byte[8192];
                    while (dis.read(buffer) != -1) {
                    }
                } finally {
                    remoteStream.close();
                }

                if (!ftp.completePendingCommand()) {
                    throw new IOException("FTP completePendingCommand() falló para: " + remoteFilePath);
                }
                return md.digest();
            }

            private boolean checksumMatch(FTPClient ftp, String remoteFilePath, File localFile) throws Exception {
                byte[] localHash = calculateLocalChecksum(localFile);
                byte[] remoteHash = calculateRemoteChecksum(ftp, remoteFilePath);
                if (remoteHash == null) {
                    log("✗ Error obteniendo checksum remoto");
                    return false;
                }
                return Arrays.equals(localHash, remoteHash);
            }

            private boolean shouldExcludeFile(String fileName) {
                String n = fileName.toLowerCase(Locale.ROOT);
                return n.endsWith(".jar") || n.endsWith(".tmp") || n.endsWith(".temp") || n.equals("update.log");
            }

            private void syncDirectoryFromFTP(FTPClient ftp, String remotePath, String localPath, String baseLocalPath)
                    throws Exception {
                File localDir = new File(localPath);
                if (!localDir.exists()) localDir.mkdirs();

                FTPFile[] remoteFiles = ftp.listFiles(remotePath);
                for (FTPFile remoteFile : remoteFiles) {
                    String fileName = remoteFile.getName();
                    if (".".equals(fileName) || "..".equals(fileName) || shouldExcludeFile(fileName)) continue;

                    String remoteFilePath = remotePath + "/" + fileName;
                    File localFile = new File(localPath, fileName);
                    remoteFilesPaths.add(localFile.getAbsolutePath());

                    if (remoteFile.isDirectory()) {
                        syncDirectoryFromFTP(ftp, remoteFilePath, localFile.getAbsolutePath(), baseLocalPath);
                        continue;
                    }
                    if (!remoteFile.isFile()) continue;

                    boolean needsDownload = false;
                    if (!localFile.exists()) {
                        needsDownload = true;
                        log("[NUEVO] " + fileName);
                    } else {
                        if (remoteFile.getSize() != localFile.length()) {
                            needsDownload = true;
                            log("[MODIFICADO] " + fileName);
                        } else {
                            try {
                                if (checksumMatch(ftp, remoteFilePath, localFile)) {
                                    filesSkipped++;
                                    filesChecksumVerified++;
                                } else {
                                    needsDownload = true;
                                    log("[MODIFICADO-CHECKSUM] " + fileName);
                                }
                            } catch (Exception e) {
                                log("⚠ Checksum falló, forzando descarga: " + e.getMessage());
                                needsDownload = true;
                            }
                        }
                    }

                    if (needsDownload) {
                        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(localFile), 65536)) {
                            if (ftp.retrieveFile(remoteFilePath, os)) {
                                filesDownloaded++;
                                if (remoteFile.getTimestamp() != null) {
                                    localFile.setLastModified(remoteFile.getTimestamp().getTimeInMillis());
                                }
                                String base = new File(baseLocalPath).getAbsolutePath();
                                String rel = localFile.getAbsolutePath().substring(base.length() + 1).replace('\\', '/');
                                changedFiles.add(rel);
                            } else {
                                log("✗ Error descargando: " + fileName);
                            }
                        }
                    }
                }
            }

            private void cleanupLocalFiles(File directory) {
                if (!directory.exists() || !directory.isDirectory()) return;
                File[] localFiles = directory.listFiles();
                if (localFiles == null) return;

                for (File localFile : localFiles) {
                    if ("update.log".equals(localFile.getName())) continue;
                    if (localFile.isDirectory()) {
                        cleanupLocalFiles(localFile);
                        String[] contents = localFile.list();
                        if (contents != null && contents.length == 0) localFile.delete();
                    } else if (!remoteFilesPaths.contains(localFile.getAbsolutePath())) {
                        if (localFile.delete()) {
                            filesDeleted++;
                            log("[ELIMINADO] " + localFile.getName());
                        }
                    }
                }
            }

            private void connectAndSyncFTP(String host, int port, String user, char[] pass, String remoteDir, String localDir)
                    throws Exception {
                FTPClient ftp = new FTPClient();
                try {
                    setStep("Conectando a servidor FTP...");
                    ftp.setConnectTimeout(10000);
                    ftp.setDataTimeout(30000);
                    ftp.setControlKeepAliveTimeout(300);

                    ftp.connect(host, port);
                    if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                        throw new IOException("FTP rechazó conexión");
                    }

                    ftp.setControlEncoding("UTF-8");
                    if (!ftp.login(user, new String(pass))) {
                        throw new IOException("Login fallido");
                    }

                    ftp.setFileType(FTP.BINARY_FILE_TYPE);
                    ftp.enterLocalPassiveMode();
                    ftp.setBufferSize(1048576);

                    String current = ftp.printWorkingDirectory();
                    if (!"/".equals(remoteDir) && !remoteDir.equals(current)) {
                        ftp.changeWorkingDirectory(remoteDir);
                    }

                    setStep("Sincronizando archivos...");
                    syncDirectoryFromFTP(ftp, remoteDir, localDir, localDir);

                    setStep("Limpiando archivos obsoletos...");
                    cleanupLocalFiles(new File(localDir));

                    log("Descargados: " + filesDownloaded + ", Sin cambios: " + filesSkipped
                            + " (checksum: " + filesChecksumVerified + "), Eliminados: " + filesDeleted);
                } finally {
                    if (ftp.isConnected()) {
                        try {
                            ftp.logout();
                        } catch (IOException ignored) {
                        }
                        ftp.disconnect();
                    }
                }
            }

            private int countFiles(File dir) {
                int c = 0;
                File[] files = dir.listFiles();
                if (files == null) return 0;
                for (File f : files) {
                    if ("update.log".equals(f.getName())) continue;
                    c += f.isDirectory() ? countFiles(f) : 1;
                }
                return c;
            }

            private void packIncrementalOrFull() throws Exception {
                File jarFile = new File(outJar);
                boolean hasDeletes = filesDeleted > 0;
                if (!jarFile.exists() || hasDeletes) {
                    int rc = runAndStream(Arrays.asList(jarExe, "cf0", outJar, "."), new File(localExploded));
                    if (rc != 0) throw new RuntimeException("jar falló con código: " + rc);
                    return;
                }
                if (changedFiles.isEmpty()) {
                    log("Sin cambios, JAR no modificado");
                    return;
                }
                int total = countFiles(new File(localExploded));
                if (changedFiles.size() > total * 0.3) {
                    int rc = runAndStream(Arrays.asList(jarExe, "cf0", outJar, "."), new File(localExploded));
                    if (rc != 0) throw new RuntimeException("jar falló con código: " + rc);
                    return;
                }
                final int BATCH_SIZE = 100;
                for (int i = 0; i < changedFiles.size(); i += BATCH_SIZE) {
                    int end = Math.min(i + BATCH_SIZE, changedFiles.size());
                    List<String> batch = changedFiles.subList(i, end);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(jarExe);
                    cmd.add("uf0");
                    cmd.add(outJar);
                    cmd.addAll(batch);
                    int rc = runAndStream(cmd, new File(localExploded));
                    if (rc != 0) {
                        rc = runAndStream(Arrays.asList(jarExe, "cf0", outJar, "."), new File(localExploded));
                        if (rc != 0) throw new RuntimeException("jar falló con código: " + rc);
                        return;
                    }
                }
            }

            @Override
            protected Void doInBackground() throws Exception {
                Files.createDirectories(Paths.get(localExploded));
                logWriter = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    log("Updater FTP Incremental v2.4");
                    extractJarIfNeeded(outJar, localExploded);
                    connectAndSyncFTP(ftpHost, ftpPort, ftpUser, ftpPass, ftpRemoteDir, localExploded);
                    setStep("Empaquetando JAR...");
                    packIncrementalOrFull();
                    setStep("Finalizando...");
                } finally {
                    Arrays.fill(ftpPass, '\0');
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    JOptionPane.showMessageDialog(null, "Actualización interrumpida", "Error", JOptionPane.ERROR_MESSAGE);
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    JOptionPane.showMessageDialog(null,
                            "Falló la actualización:\n" + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    try {
                        if (logWriter != null) logWriter.close();
                    } catch (IOException ignored) {
                    }
                    new javax.swing.Timer(1500, e -> {
                        ((javax.swing.Timer) e.getSource()).stop();
                        System.exit(0);
                    }).start();
                }
            }
        }.execute();
    }

    private static String resolveJarCommand() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            Path jarPath = Paths.get(javaHome, "bin", isWindows() ? "jar.exe" : "jar");
            if (Files.exists(jarPath)) return jarPath.toString();
        }
        return isWindows() ? "jar.exe" : "jar";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static JLabel buildLogo(String path, int maxW) {
        return buildLogo(path, maxW, LOGO_MAX_H);
    }

    private static JLabel buildLogo(String path, int maxW, int maxH) {
        try {
            File f = new File(path);
            if (f.exists()) {
                ImageIcon icon = new ImageIcon(path);
                int w = icon.getIconWidth();
                int h = icon.getIconHeight();
                if (w > 0 && h > 0) {
                    double scaleW = (w > maxW) ? (maxW / (double) w) : 1.0;
                    double scaleH = (h > maxH) ? (maxH / (double) h) : 1.0;
                    double scale = Math.min(scaleW, scaleH);
                    if (scale < 1.0) {
                        icon = new ImageIcon(icon.getImage().getScaledInstance(
                                (int) Math.round(w * scale), (int) Math.round(h * scale), Image.SCALE_SMOOTH));
                    }
                }
                JLabel lbl = new JLabel(icon, SwingConstants.CENTER);
                lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
                return lbl;
            }
        } catch (Exception ignored) {
        }
        JLabel lbl = new JLabel("Actualizando...", SwingConstants.CENTER);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 16f));
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        return lbl;
    }

    private static JComponent buildModernLoader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        ModernSpinner spinner = new ModernSpinner(48, 4, 12, 1100);
        spinner.setForeground(BRAND);
        spinner.setBorder(BorderFactory.createEmptyBorder(8, 0, 6, 0));
        JLabel caption = new JLabel("Procesando…", SwingConstants.CENTER);
        caption.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(spinner, BorderLayout.CENTER);
        p.add(caption, BorderLayout.SOUTH);
        return p;
    }

    static class ModernSpinner extends JComponent {
        private final int size;
        private final int thickness;
        private final int segments;
        private final int periodMs;
        private final javax.swing.Timer timer;

        ModernSpinner(int size, int thickness, int segments, int periodMs) {
            this.size = size;
            this.thickness = Math.max(1, thickness);
            this.segments = Math.max(6, segments);
            this.periodMs = Math.max(300, periodMs);
            setOpaque(false);
            timer = new javax.swing.Timer(16, e -> repaint());
            timer.start();
            addHierarchyListener(ev -> {
                if ((ev.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                    if (isDisplayable()) timer.start(); else timer.stop();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(size, size); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight(), s = Math.min(w, h);
            int pad = thickness + 2, d = s - pad * 2;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color base = getForeground() != null ? getForeground() : BRAND;
                float progress = ((System.nanoTime() / 1_000_000L) % periodMs) / (float) periodMs;
                float baseAngle = progress * 360f;
                float step = 360f / segments;
                float arcSpan = Math.max(4f, step - 3f);
                int x = (w - d) / 2, y = (h - d) / 2;
                g2.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < segments; i++) {
                    float t = i / (float) segments;
                    float alpha = 0.15f + (1f - t) * 0.85f;
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
                    float k = 0.08f * (1f - t);
                    g2.setColor(new Color(
                            Math.min(255, (int) (base.getRed() + (255 - base.getRed()) * k)),
                            Math.min(255, (int) (base.getGreen() + (255 - base.getGreen()) * k)),
                            Math.min(255, (int) (base.getBlue() + (255 - base.getBlue()) * k))));
                    g2.drawArc(x, y, d, d, Math.round(baseAngle - i * step), Math.round(arcSpan));
                }
            } finally {
                g2.dispose();
            }
        }
    }

    static class ModernIndeterminateBar extends JComponent {
        private final int heightPx;
        private final int periodMs;
        private final javax.swing.Timer timer;

        ModernIndeterminateBar(int heightPx, int periodMs) {
            this.heightPx = Math.max(4, heightPx);
            this.periodMs = Math.max(400, periodMs);
            setOpaque(false);
            timer = new javax.swing.Timer(16, e -> repaint());
            timer.start();
            addHierarchyListener(ev -> {
                if ((ev.getChangeFlags() & HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                    if (isDisplayable()) timer.start(); else timer.stop();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() { return new Dimension(10, heightPx); }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth(), h = getHeight(), bh = heightPx, y = (h - bh) / 2, arc = bh;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bgBase = getParent() != null ? getParent().getBackground() : getBackground();
                if (bgBase == null) bgBase = UIManager.getColor("Panel.background");
                boolean dark = isDark(bgBase);
                g2.setColor(dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 40));
                g2.fillRoundRect(0, y, w, bh, arc, arc);

                float progress = ((System.nanoTime() / 1_000_000L) % periodMs) / (float) periodMs;
                int segW = Math.max(bh * 6, (int) (w * 0.28f));
                int x = (int) (progress * (w + segW)) - segW;
                Color base = getForeground() != null ? getForeground() : BRAND;

                g2.setComposite(AlphaComposite.SrcOver.derive(0.85f));
                g2.setColor(base);
                g2.fillRoundRect(x, y, segW, bh, arc, arc);

                g2.setComposite(AlphaComposite.SrcOver.derive(0.35f));
                g2.fillRoundRect(x - bh / 2, y, segW + bh, bh, arc, arc);
            } finally {
                g2.dispose();
            }
        }

        private boolean isDark(Color c) {
            double l = (0.2126 * c.getRed() + 0.7152 * c.getGreen() + 0.0722 * c.getBlue()) / 255.0;
            return l < 0.5;
        }
    }
}
