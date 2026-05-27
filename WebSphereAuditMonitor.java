/*******************************************************************************
 * Copyright (c) 2026 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility method for Java 8 compatibility
 */
class StringUtils {
    static String repeat(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}

/**
 * WebSphere Configuration Audit Monitor
 *
 * Usage: java WebSphereAuditMonitor [config.properties]
 *
 * Arguments:
 * - config.properties: Configuration file path (optional, defaults to config.properties)
 *
 * Configuration properties:
 * - checkpoint.directory=/dmgr/config/temp/download/cells/was90cell/repository/checkpoints
 * - wsadmin.path=/opt/IBM/WebSphere/AppServer/bin
 * - audit.log.path=./audit.log
 * - schedule.interval.minutes=60
 * - last.processed.timestamp.file=.last_processed_timestamp
 */
public class WebSphereAuditMonitor {
    
    private static final String DEFAULT_CONFIG = "config.properties";
    private Properties config;
    private String checkpointDir;
    private String wsadminPath;
    private String auditLogPath;
    private int scheduleIntervalMinutes;
    private String lastProcessedTimestampFile;
    private long lastProcessedTimestamp = 0;
    private long maxLogSizeMB;
    private int maxLogFiles;
    private String archivedLogsDir;
    
    public WebSphereAuditMonitor(String configFile) throws IOException {
        loadConfiguration(configFile);
        loadLastProcessedTimestamp();
    }
    
    private void loadConfiguration(String configFile) throws IOException {
        config = new Properties();
        File file = new File(configFile);
        
        if (!file.exists()) {
            // Create default configuration file
            createDefaultConfig(configFile);
            System.out.println("Created default configuration file: " + configFile);
            System.out.println("Please update the configuration and run again.");
            System.exit(0);
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            config.load(fis);
        }
        
        checkpointDir = config.getProperty("checkpoint.directory",
            "/dmgr/config/temp/download/cells/was90cell/repository/checkpoints");
        wsadminPath = config.getProperty("wsadmin.path", "/opt/IBM/WebSphere/AppServer/bin");
        auditLogPath = config.getProperty("audit.log.path", "./audit.log");
        scheduleIntervalMinutes = Integer.parseInt(config.getProperty("schedule.interval.minutes", "60"));
        lastProcessedTimestampFile = config.getProperty("last.processed.timestamp.file", ".last_processed_timestamp");
        maxLogSizeMB = Long.parseLong(config.getProperty("audit.log.max.size.mb", "5"));
        maxLogFiles = Integer.parseInt(config.getProperty("audit.log.max.files", "10"));
        archivedLogsDir = config.getProperty("audit.log.archive.dir", "./archived_logs");
    }
    
    private void createDefaultConfig(String configFile) throws IOException {
        Properties defaultConfig = new Properties();
        defaultConfig.setProperty("checkpoint.directory", "/dmgr/config/temp/download/cells/was90cell/repository/checkpoints");
        defaultConfig.setProperty("wsadmin.path", "/opt/IBM/WebSphere/AppServer/bin");
        defaultConfig.setProperty("audit.log.path", "./audit.log");
        defaultConfig.setProperty("schedule.interval.minutes", "60");
        defaultConfig.setProperty("last.processed.timestamp.file", ".last_processed_timestamp");
        defaultConfig.setProperty("audit.log.max.size.mb", "5");
        defaultConfig.setProperty("audit.log.max.files", "10");
        defaultConfig.setProperty("audit.log.archive.dir", "./archived_logs");
        
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            defaultConfig.store(fos, "WebSphere Audit Monitor Configuration");
        }
    }
    
    private void loadLastProcessedTimestamp() {
        File timestampFile = new File(lastProcessedTimestampFile);
        if (timestampFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(timestampFile))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    lastProcessedTimestamp = Long.parseLong(line.trim());
                    System.out.println("Loaded last processed timestamp: " + lastProcessedTimestamp);
                }
            } catch (Exception e) {
                System.err.println("Error loading last processed timestamp: " + e.getMessage());
            }
        }
    }
    
    private void saveLastProcessedTimestamp(long timestamp) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(lastProcessedTimestampFile))) {
            writer.println(timestamp);
            lastProcessedTimestamp = timestamp;
            System.out.println("Saved last processed timestamp: " + timestamp);
        } catch (IOException e) {
            System.err.println("Error saving last processed timestamp: " + e.getMessage());
        }
    }
    
    public void start() {
        System.out.println("WebSphere Audit Monitor started");
        System.out.println("Checkpoint Directory: " + checkpointDir);
        System.out.println("Schedule Interval: " + scheduleIntervalMinutes + " minutes");
        System.out.println("Audit Log: " + auditLogPath);
        
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        
        // Run immediately on start
        processCheckpoints();
        
        // Schedule periodic execution
        scheduler.scheduleAtFixedRate(() -> {
            try {
                processCheckpoints();
            } catch (Exception e) {
                System.err.println("Error in scheduled execution: " + e.getMessage());
                e.printStackTrace();
            }
        }, scheduleIntervalMinutes, scheduleIntervalMinutes, TimeUnit.MINUTES);
        
        // Keep the program running
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            scheduler.shutdown();
        }
    }
    
    private void processCheckpoints() {
        System.out.println("\n=== Processing Checkpoints at " + new Date() + " ===");
        
        try {
            // Step 1: Scan checkpoint directory
            List<DeltaDirectory> deltaDirectories = scanCheckpointDirectory();
            
            if (deltaDirectories.isEmpty()) {
                System.out.println("No new delta directories found.");
                return;
            }
            
            // Step 2: Filter directories based on last processed timestamp
            List<DeltaDirectory> newDirectories = deltaDirectories.stream()
                .filter(d -> d.timestamp > lastProcessedTimestamp)
                .sorted(Comparator.comparingLong(d -> d.timestamp))
                .collect(Collectors.toList());
            
            if (newDirectories.isEmpty()) {
                System.out.println("No new directories to process.");
                return;
            }
            
            System.out.println("Found " + newDirectories.size() + " new delta directories to process");
            
            // Step 3: Process each delta directory
            List<AuditEntry> auditEntries = new ArrayList<>();
            long maxTimestamp = lastProcessedTimestamp;
            
            for (DeltaDirectory deltaDir : newDirectories) {
                System.out.println("\nProcessing: " + deltaDir.name);
                
                try {
                    // Extract checkpoint to zip file
                    File zipFile = extractCheckpoint(deltaDir);
                    
                    if (zipFile != null && zipFile.exists()) {
                        // Process the zip file
                        List<AuditEntry> entries = processZipFile(zipFile, deltaDir);
                        auditEntries.addAll(entries);
                        
                        // Clean up zip file
                        zipFile.delete();
                    }
                    
                    maxTimestamp = Math.max(maxTimestamp, deltaDir.timestamp);
                    
                } catch (Exception e) {
                    System.err.println("Error processing " + deltaDir.name + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
            
            // Step 4: Write audit log
            if (!auditEntries.isEmpty()) {
                writeAuditLog(auditEntries);
                saveLastProcessedTimestamp(maxTimestamp);
            }
            
            System.out.println("\n=== Processing Complete ===");
            
        } catch (Exception e) {
            System.err.println("Error in processCheckpoints: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private List<DeltaDirectory> scanCheckpointDirectory() throws IOException {
        List<DeltaDirectory> deltaDirectories = new ArrayList<>();
        File checkpointDirFile = new File(checkpointDir);
        
        if (!checkpointDirFile.exists() || !checkpointDirFile.isDirectory()) {
            System.err.println("Checkpoint directory does not exist: " + checkpointDir);
            return deltaDirectories;
        }
        
        File[] files = checkpointDirFile.listFiles();
        if (files == null) {
            return deltaDirectories;
        }
        
        for (File file : files) {
            if (file.isDirectory() && file.getName().startsWith("Delta-")) {
                try {
                    String timestampStr = file.getName().substring(6); // Remove "Delta-"
                    long timestamp = Long.parseLong(timestampStr);
                    deltaDirectories.add(new DeltaDirectory(file.getName(), timestamp, file.getAbsolutePath()));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid delta directory name: " + file.getName());
                }
            }
        }
        
        return deltaDirectories;
    }
    
    private File extractCheckpoint(DeltaDirectory deltaDir) {
        try {
            // Create Python script
            File scriptFile = createWsadminScript(deltaDir.name);
            
            // Execute wsadmin command
            String wsadminScript = wsadminPath + "/wsadmin.sh";
            String zipFileName = deltaDir.name + ".zip";
            System.out.println("  Python script path: " + scriptFile.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(
                wsadminScript,
                "-conntype", "NONE",
                "-f", scriptFile.getAbsolutePath()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("  wsadmin: " + line);
            }
            
            int exitCode = process.waitFor();
            scriptFile.delete();
            
            if (exitCode == 0) {
                File zipFile = new File(zipFileName);
                if (zipFile.exists()) {
                    System.out.println("  Successfully extracted checkpoint to: " + zipFileName);
                    return zipFile;
                } else {
                    System.err.println("  Zip file not created: " + zipFileName);
                }
            } else {
                System.err.println("  wsadmin command failed with exit code: " + exitCode);
            }
            
        } catch (Exception e) {
            System.err.println("Error extracting checkpoint: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    private File createWsadminScript(String checkpointName) throws IOException {
        File scriptFile = File.createTempFile("extract_checkpoint_", ".py");
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(scriptFile))) {
            writer.println("# WebSphere Checkpoint Extraction Script");
            writer.println("checkpointName = '" + checkpointName + "'");
            writer.println("zipFileName = checkpointName + '.zip'");
            writer.println("AdminTask.extractRepositoryCheckpoint('[-checkpointName ' + checkpointName + ' -extractToFile ' + zipFileName + ']')");
            writer.println("print 'Checkpoint extracted to: ' + zipFileName");
        }
        
        return scriptFile;
    }
    
    /**
     * Read user.id file with proper encoding handling for z/OS EBCDIC
     * Attempts to detect and convert EBCDIC encoding to UTF-8
     */
    private String readUserIdWithEncoding(File userIdFile) throws IOException {
        byte[] bytes = Files.readAllBytes(userIdFile.toPath());
        
        // Try to detect if content is EBCDIC by checking for common EBCDIC patterns
        // EBCDIC space is 0x40, and printable characters are in different ranges
        boolean likelyEbcdic = isLikelyEbcdic(bytes);
        
        String userId;
        if (likelyEbcdic) {
            // Convert from EBCDIC to UTF-8
            try {
                // IBM EBCDIC charset (Cp037 is US EBCDIC, Cp1047 is Latin-1 EBCDIC)
                // Try Cp1047 first (most common for z/OS)
                Charset ebcdicCharset = Charset.forName("Cp1047");
                userId = new String(bytes, ebcdicCharset);
                System.out.println("  Detected EBCDIC encoding, converted to UTF-8");
            } catch (Exception e) {
                // Fallback to Cp037 if Cp1047 is not available
                try {
                    Charset ebcdicCharset = Charset.forName("Cp037");
                    userId = new String(bytes, ebcdicCharset);
                    System.out.println("  Detected EBCDIC encoding (Cp037), converted to UTF-8");
                } catch (Exception e2) {
                    // If EBCDIC charsets are not available, use default
                    System.err.println("  Warning: EBCDIC charset not available, using default encoding");
                    userId = new String(bytes, StandardCharsets.UTF_8);
                }
            }
        } else {
            // Assume UTF-8 or ASCII
            userId = new String(bytes, StandardCharsets.UTF_8);
        }
        
        return userId.trim();
    }
    
    /**
     * Heuristic to detect if byte array is likely EBCDIC encoded
     * EBCDIC has different byte ranges for printable characters
     */
    private boolean isLikelyEbcdic(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        
        int ebcdicIndicators = 0;
        int totalBytes = Math.min(bytes.length, 100); // Check first 100 bytes
        
        for (int i = 0; i < totalBytes; i++) {
            int b = bytes[i] & 0xFF; // Convert to unsigned
            
            // EBCDIC space is 0x40 (64 decimal)
            // EBCDIC lowercase letters: a-i (0x81-0x89), j-r (0x91-0x99), s-z (0xA2-0xA9)
            // EBCDIC uppercase letters: A-I (0xC1-0xC9), J-R (0xD1-0xD9), S-Z (0xE2-0xE9)
            // EBCDIC digits: 0-9 (0xF0-0xF9)
            
            if (b == 0x40 || // EBCDIC space
                (b >= 0x81 && b <= 0x89) || // a-i
                (b >= 0x91 && b <= 0x99) || // j-r
                (b >= 0xA2 && b <= 0xA9) || // s-z
                (b >= 0xC1 && b <= 0xC9) || // A-I
                (b >= 0xD1 && b <= 0xD9) || // J-R
                (b >= 0xE2 && b <= 0xE9) || // S-Z
                (b >= 0xF0 && b <= 0xF9)) { // 0-9
                ebcdicIndicators++;
            }
            
            // ASCII/UTF-8 printable range is 0x20-0x7E
            // If we see characters outside this range that match EBCDIC patterns, likely EBCDIC
            if (b > 0x7E && (
                (b >= 0x81 && b <= 0x89) ||
                (b >= 0x91 && b <= 0x99) ||
                (b >= 0xA2 && b <= 0xA9) ||
                (b >= 0xC1 && b <= 0xC9) ||
                (b >= 0xD1 && b <= 0xD9) ||
                (b >= 0xE2 && b <= 0xE9))) {
                ebcdicIndicators += 2; // Weight these more heavily
            }
        }
        
        // If more than 30% of bytes match EBCDIC patterns, consider it EBCDIC
        return (ebcdicIndicators * 100.0 / totalBytes) > 30;
    }
    
    private List<AuditEntry> processZipFile(File zipFile, DeltaDirectory deltaDir) throws IOException {
        List<AuditEntry> auditEntries = new ArrayList<>();
        
        // Create temp directory for extraction
        File tempDir = Files.createTempDirectory("checkpoint_").toFile();
        
        try {
            // Unzip the file
            unzip(zipFile, tempDir);
            
            // Look for user.id file
            File userIdFile = new File(tempDir, "user.id");
            String userId = "Unknown";
            if (userIdFile.exists()) {
                userId = readUserIdWithEncoding(userIdFile);
            }
            
            // Process before and after directories
            File beforeDir = new File(tempDir, "before");
            File afterDir = new File(tempDir, "after");
            
            if (beforeDir.exists() && afterDir.exists()) {
                Map<String, File> beforeFiles = getFilesMap(beforeDir);
                Map<String, File> afterFiles = getFilesMap(afterDir);
                
                // Find all unique file paths
                Set<String> allPaths = new HashSet<>();
                allPaths.addAll(beforeFiles.keySet());
                allPaths.addAll(afterFiles.keySet());
                
                for (String path : allPaths) {
                    File beforeFile = beforeFiles.get(path);
                    File afterFile = afterFiles.get(path);
                    
                    String changeType;
                    List<String> differences = new ArrayList<>();
                    
                    if (beforeFile == null && afterFile != null) {
                        changeType = "ADDED";
                        differences.add("File created");
                    } else if (beforeFile != null && afterFile == null) {
                        changeType = "DELETED";
                        differences.add("File deleted");
                    } else {
                        differences = compareFiles(beforeFile, afterFile);
                        if (differences.isEmpty()) {
                            continue; // No actual changes
                        }
                        
                        // Determine change type based on the differences found
                        changeType = determineChangeType(differences);
                    }
                    
                    AuditEntry entry = new AuditEntry(
                        deltaDir.timestamp,
                        userId,
                        path,
                        changeType,
                        differences
                    );
                    auditEntries.add(entry);
                }
            }
            
        } finally {
            // Clean up temp directory
            deleteDirectory(tempDir);
        }
        
        return auditEntries;
    }
    
    private void unzip(File zipFile, File destDir) throws IOException {
        byte[] buffer = new byte[1024];
        
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry zipEntry = zis.getNextEntry();
            
            while (zipEntry != null) {
                File newFile = new File(destDir, zipEntry.getName());
                
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    // Create parent directories
                    new File(newFile.getParent()).mkdirs();
                    
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                
                zipEntry = zis.getNextEntry();
            }
            
            zis.closeEntry();
        }
    }
    
    private Map<String, File> getFilesMap(File directory) {
        Map<String, File> filesMap = new HashMap<>();
        collectFiles(directory, directory, filesMap);
        return filesMap;
    }
    
    private void collectFiles(File rootDir, File currentDir, Map<String, File> filesMap) {
        File[] files = currentDir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                collectFiles(rootDir, file, filesMap);
            } else {
                String relativePath = rootDir.toPath().relativize(file.toPath()).toString();
                filesMap.put(relativePath, file);
            }
        }
    }
    
    /**
     * Determine the overall change type based on the differences found
     */
    private String determineChangeType(List<String> differences) {
        boolean hasAdded = false;
        boolean hasDeleted = false;
        boolean hasModified = false;
        
        for (String diff : differences) {
            if (diff.contains(" added:")) {
                hasAdded = true;
            } else if (diff.contains(" deleted:")) {
                hasDeleted = true;
            } else if (diff.contains(" modified:")) {
                hasModified = true;
            }
        }
        
        // If only additions, mark as ADDED
        if (hasAdded && !hasDeleted && !hasModified) {
            return "ADDED";
        }
        // If only deletions, mark as DELETED
        else if (hasDeleted && !hasAdded && !hasModified) {
            return "DELETED";
        }
        // Otherwise it's a modification (or mixed changes)
        else {
            return "MODIFIED";
        }
    }
    
    private List<String> compareFiles(File beforeFile, File afterFile) throws IOException {
        List<String> differences = new ArrayList<>();
        
        List<String> beforeLines = Files.readAllLines(beforeFile.toPath());
        List<String> afterLines = Files.readAllLines(afterFile.toPath());
        
        // Use a more sophisticated diff algorithm
        List<DiffEntry> diffEntries = computeDiff(beforeLines, afterLines);
        
        for (DiffEntry entry : diffEntries) {
            switch (entry.type) {
                case ADDED:
                    differences.add("Line " + entry.lineNumber + " added: " + entry.content);
                    break;
                case DELETED:
                    differences.add("Line " + entry.lineNumber + " deleted: " + entry.content);
                    break;
                case MODIFIED:
                    differences.add("Line " + entry.lineNumber + " modified:");
                    differences.add("  Before: " + entry.beforeContent);
                    differences.add("  After:  " + entry.content);
                    break;
            }
        }
        
        return differences;
    }
    
    private List<DiffEntry> computeDiff(List<String> beforeLines, List<String> afterLines) {
        List<DiffEntry> diffEntries = new ArrayList<>();
        
        // Use LCS (Longest Common Subsequence) based diff algorithm
        int[][] lcs = computeLCS(beforeLines, afterLines);
        
        // Backtrack through LCS to identify changes
        int i = beforeLines.size();
        int j = afterLines.size();
        
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && beforeLines.get(i - 1).equals(afterLines.get(j - 1))) {
                // Lines are the same, move diagonally
                i--;
                j--;
            } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
                // Line was added in after
                diffEntries.add(0, new DiffEntry(DiffType.ADDED, j, afterLines.get(j - 1), null));
                j--;
            } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
                // Line was deleted from before
                diffEntries.add(0, new DiffEntry(DiffType.DELETED, i, beforeLines.get(i - 1), null));
                i--;
            }
        }
        
        // Now check for modifications (lines at same position but different content)
        // This is a second pass to catch true modifications vs add/delete pairs
        List<DiffEntry> refinedEntries = new ArrayList<>();
        for (int k = 0; k < diffEntries.size(); k++) {
            DiffEntry current = diffEntries.get(k);
            
            // Look for adjacent delete+add which might be a modification
            if (current.type == DiffType.DELETED && k + 1 < diffEntries.size()) {
                DiffEntry next = diffEntries.get(k + 1);
                if (next.type == DiffType.ADDED &&
                    Math.abs(current.lineNumber - next.lineNumber) <= 1) {
                    // This is likely a modification
                    refinedEntries.add(new DiffEntry(DiffType.MODIFIED, next.lineNumber,
                                                     next.content, current.content));
                    k++; // Skip the next entry as we've combined them
                    continue;
                }
            }
            
            refinedEntries.add(current);
        }
        
        return refinedEntries;
    }
    
    /**
     * Compute Longest Common Subsequence (LCS) matrix
     * This helps identify which lines are common between before and after
     */
    private int[][] computeLCS(List<String> beforeLines, List<String> afterLines) {
        int m = beforeLines.size();
        int n = afterLines.size();
        int[][] lcs = new int[m + 1][n + 1];
        
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (beforeLines.get(i - 1).equals(afterLines.get(j - 1))) {
                    lcs[i][j] = lcs[i - 1][j - 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i - 1][j], lcs[i][j - 1]);
                }
            }
        }
        
        return lcs;
    }
    
    enum DiffType {
        ADDED, DELETED, MODIFIED
    }
    
    static class DiffEntry {
        DiffType type;
        int lineNumber;
        String content;
        String beforeContent;
        
        DiffEntry(DiffType type, int lineNumber, String content, String beforeContent) {
            this.type = type;
            this.lineNumber = lineNumber;
            this.content = content;
            this.beforeContent = beforeContent;
        }
    }
    
    private void writeAuditLog(List<AuditEntry> auditEntries) {
        // Check if log rolling is needed before writing
        rollLogFileIfNeeded();
        
        // Sort by timestamp
        auditEntries.sort(Comparator.comparingLong(e -> e.timestamp));
        
        try (PrintWriter writer = new PrintWriter(new FileWriter(auditLogPath, true))) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            
            writer.println("\n" + StringUtils.repeat("=", 80));
            writer.println("Audit Log Entry - " + sdf.format(new Date()));
            writer.println(StringUtils.repeat("=", 80));
            
            for (AuditEntry entry : auditEntries) {
                writer.println("\nTimestamp: " + sdf.format(new Date(entry.timestamp)));
                writer.println("User: " + entry.userId);
                writer.println("Cell: " + entry.cellName);
                writer.println("File: " + entry.filePath);
                writer.println("Change Type: " + entry.changeType);
                writer.println("Changes:");
                for (String diff : entry.differences) {
                    writer.println("  " + diff);
                }
                writer.println(StringUtils.repeat("-", 80));
            }
            
            System.out.println("Audit log updated: " + auditLogPath);
            System.out.println("Total changes logged: " + auditEntries.size());
            
        } catch (IOException e) {
            System.err.println("Error writing audit log: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void rollLogFileIfNeeded() {
        File logFile = new File(auditLogPath);
        
        // Check if file exists and size exceeds limit
        if (!logFile.exists() || logFile.length() < maxLogSizeMB * 1024 * 1024) {
            return; // No rolling needed
        }
        
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
            String timestamp = sdf.format(new Date());
            
            // Count existing rolled log files
            File logDir = logFile.getParentFile();
            if (logDir == null) {
                logDir = new File(".");
            }
            
            String logBaseName = logFile.getName();
            File[] existingLogs = logDir.listFiles((dir, name) ->
                name.startsWith(logBaseName + ".") && !name.endsWith(".zip"));
            
            int currentLogCount = existingLogs != null ? existingLogs.length : 0;
            
            // Roll current log file with timestamp
            String rolledLogName = logBaseName + "." + timestamp;
            File rolledLog = new File(logDir, rolledLogName);
            
            if (logFile.renameTo(rolledLog)) {
                System.out.println("Rolled audit log: " + logFile.getName() + " -> " + rolledLogName);
                
                // Check if we've reached the max file count
                if (currentLogCount >= maxLogFiles) {
                    System.out.println("Maximum log files (" + maxLogFiles + ") reached. Archiving old logs...");
                    archiveOldLogs(logDir, logBaseName);
                }
            } else {
                System.err.println("Failed to roll audit log file");
            }
            
        } catch (Exception e) {
            System.err.println("Error during log rolling: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void archiveOldLogs(File logDir, String logBaseName) {
        try {
            // Create archive directory if it doesn't exist
            File archiveDir = new File(archivedLogsDir);
            if (!archiveDir.exists()) {
                archiveDir.mkdirs();
            }
            
            // Get all rolled log files (excluding already zipped ones)
            File[] rolledLogs = logDir.listFiles((dir, name) ->
                name.startsWith(logBaseName + ".") && !name.endsWith(".zip"));
            
            if (rolledLogs == null || rolledLogs.length == 0) {
                return;
            }
            
            // Sort by last modified time (oldest first)
            Arrays.sort(rolledLogs, Comparator.comparingLong(File::lastModified));
            
            // Create timestamped archive folder
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
            String archiveTimestamp = sdf.format(new Date());
            File archiveFolder = new File(archiveDir, "audit_logs_" + archiveTimestamp);
            archiveFolder.mkdirs();
            
            // Move old log files to archive folder
            int filesToArchive = rolledLogs.length - maxLogFiles + 1;
            if (filesToArchive <= 0) {
                return;
            }
            
            List<File> archivedFiles = new ArrayList<>();
            for (int i = 0; i < filesToArchive && i < rolledLogs.length; i++) {
                File oldLog = rolledLogs[i];
                File archivedLog = new File(archiveFolder, oldLog.getName());
                if (oldLog.renameTo(archivedLog)) {
                    archivedFiles.add(archivedLog);
                    System.out.println("Moved to archive: " + oldLog.getName());
                }
            }
            
            // Zip the archive folder
            if (!archivedFiles.isEmpty()) {
                String zipFileName = "audit_logs_" + archiveTimestamp + ".zip";
                File zipFile = new File(archiveDir, zipFileName);
                zipDirectory(archiveFolder, zipFile);
                
                // Delete the archive folder after zipping
                deleteDirectory(archiveFolder);
                
                System.out.println("Created archive: " + zipFileName + " (" + archivedFiles.size() + " files)");
            }
            
        } catch (Exception e) {
            System.err.println("Error archiving old logs: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos)) {
            
            File[] files = sourceDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        addFileToZip(file, file.getName(), zos);
                    }
                }
            }
        }
    }
    
    private void addFileToZip(File file, String fileName, java.util.zip.ZipOutputStream zos) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            java.util.zip.ZipEntry zipEntry = new java.util.zip.ZipEntry(fileName);
            zos.putNextEntry(zipEntry);
            
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                zos.write(buffer, 0, length);
            }
            
            zos.closeEntry();
        }
    }
    
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
    
    // Inner classes
    private static class DeltaDirectory {
        String name;
        long timestamp;
        String path;
        
        DeltaDirectory(String name, long timestamp, String path) {
            this.name = name;
            this.timestamp = timestamp;
            this.path = path;
        }
    }
    
    private static class AuditEntry {
        long timestamp;
        String userId;
        String filePath;
        String cellName;
        String changeType;
        List<String> differences;
        
        AuditEntry(long timestamp, String userId, String filePath, String changeType, List<String> differences) {
            this.timestamp = timestamp;
            this.userId = userId;
            this.filePath = filePath;
            this.cellName = extractCellName(filePath);
            this.changeType = changeType;
            this.differences = differences;
        }
        
        private static String extractCellName(String filePath) {
            // Extract cell name from path like: cells/was90cell/nodes/...
            // Cell name is between first and second /
            if (filePath != null && filePath.startsWith("cells/")) {
                String[] parts = filePath.split("/");
                if (parts.length >= 2) {
                    return parts[1]; // Return was90cell
                }
            }
            return "Unknown";
        }
    }
    
    public static void main(String[] args) {
        String configFile = args.length > 0 ? args[0] : DEFAULT_CONFIG;
        
        try {
            WebSphereAuditMonitor monitor = new WebSphereAuditMonitor(configFile);
            monitor.start();
        } catch (Exception e) {
            System.err.println("Error starting WebSphere Audit Monitor: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

// Made with Bob
