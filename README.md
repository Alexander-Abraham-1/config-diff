# WebSphere Audit Monitor

A single-file Java application that monitors WebSphere configuration changes by processing repository checkpoints and generating audit logs.

## Features

- Scans checkpoint directories for Delta-* folders
- Tracks processed checkpoints to avoid reprocessing
- Extracts checkpoints using wsadmin.sh
- Compares before/after configurations
- Generates detailed audit logs with user information and timestamps
- Runs on a scheduled interval
- All configuration via properties file

## Requirements

- Java 8 or higher
- WebSphere Application Server with wsadmin.sh
- Access to checkpoint directory
- Valid WebSphere admin credentials

## Quick Start

### 1. Compile the Program

```bash
javac WebSphereAuditMonitor.java
```

### 2. Run the Program

#### Option A: Using Shell Scripts (Recommended)

Make scripts executable (first time only):
```bash
chmod +x start-monitor.sh stop-monitor.sh status-monitor.sh
```

Start the monitor in background:
```bash
./start-monitor.sh
```

Or with custom config file:
```bash
./start-monitor.sh /path/to/config.properties
```

Check status:
```bash
./status-monitor.sh
```

Stop the monitor:
```bash
./stop-monitor.sh
```

View live logs:
```bash
tail -f monitor.log
```

#### Option B: Direct Java Execution

First run will create a default configuration file:

```bash
java WebSphereAuditMonitor
```

Or specify a custom config file:

```bash
java WebSphereAuditMonitor myconfig.properties
```

Run in background manually:
```bash
nohup java WebSphereAuditMonitor > monitor.log 2>&1 &
echo $! > .monitor.pid
```

### 3. Configure

Edit the generated `config.properties` file:

```properties
# Directory containing Delta-* checkpoint folders
checkpoint.directory=/dmgr/config/temp/download/cells/was90cell/repository/checkpoints

# Path to WebSphere wsadmin.sh directory
wsadmin.path=/opt/IBM/WebSphere/AppServer/bin

# Output audit log file path
audit.log.path=./audit.log

# Schedule interval in minutes
schedule.interval.minutes=60

# File to track last processed timestamp
last.processed.timestamp.file=.last_processed_timestamp

# Log rolling configuration
audit.log.max.size.mb=5          # Maximum log file size before rolling (in MB)
audit.log.max.files=10           # Maximum number of rolled log files to keep
audit.log.archive.dir=./archived_logs  # Directory for archived log files
```

**Note:** The monitor uses wsadmin with `-conntype NONE` (offline mode) to extract checkpoints without requiring an active WebSphere connection.

### Log Rolling Feature

The audit log automatically rolls when it reaches the configured size limit:

**How it works:**
1. When `audit.log` reaches the size limit (default: 5MB), it's renamed with a timestamp
   - Example: `audit.log.20260415-143022`
2. A new `audit.log` file is created for new entries
3. When the number of rolled files exceeds the limit (default: 10):
   - Oldest log files are moved to an archive folder
   - Archive folder is named with timestamp: `audit_logs_20260415-143022`
   - Archive folder is zipped: `audit_logs_20260415-143022.zip`
   - Original files are deleted after zipping
4. All history is preserved in timestamped zip files in the `archived_logs` directory

**Benefits:**
- Prevents audit.log from growing indefinitely
- Maintains complete history (nothing is deleted)
- Automatic archiving and compression
- Easy to manage and review old logs

### 4. Run Again

After configuration, run the program again:

```bash
java WebSphereAuditMonitor
```

Or with custom config file:

```bash
java WebSphereAuditMonitor myconfig.properties
```

The program will:
- Run immediately on startup
- Continue running and check for new checkpoints every N minutes (configured interval)
- Process only new Delta directories since last run
- Generate audit logs showing all configuration changes

## How It Works

1. **Scan**: Scans the checkpoint directory for `Delta-<timestamp>` folders
2. **Filter**: Identifies new checkpoints since last run (tracked in `.last_processed_timestamp`)
3. **Extract**: For each new checkpoint, executes wsadmin.sh to extract it to a zip file
4. **Unzip**: Extracts the zip file to access `before/`, `after/` folders and `user.id`
5. **Compare**: Compares files between before and after directories
6. **Log**: Generates audit log entries with:
   - Timestamp of change
   - User who made the change
   - File path
   - Change type (ADDED/MODIFIED/DELETED)
   - Detailed line-by-line differences
7. **Track**: Updates last processed timestamp for next run

## Audit Log Format

```
================================================================================
Audit Log Entry - 2025-12-14 15:25:30
================================================================================

Timestamp: 2025-12-14 15:20:15
User: wasadmin
Cell: cell01
File: cells/cell01/applications/app.xml
Change Type: MODIFIED
Changes:
  Line 15 modified:
    Before: <property name="timeout" value="30"/>
    After:  <property name="timeout" value="60"/>
--------------------------------------------------------------------------------

Timestamp: 2025-12-14 15:21:45
User: wasadmin
Cell: cell01
File: cells/cell01/security/security.xml
Change Type: ADDED
Changes:
  File created
--------------------------------------------------------------------------------
```

## Background Process Management

### Using Provided Scripts (Easiest)

Three shell scripts are provided for easy management:

**start-monitor.sh** - Start the monitor in background
```bash
./start-monitor.sh admin mypassword
./start-monitor.sh admin mypassword /path/to/config.properties
```

**stop-monitor.sh** - Stop the running monitor
```bash
./stop-monitor.sh
```

**status-monitor.sh** - Check if monitor is running
```bash
./status-monitor.sh
```

The scripts automatically:
- Compile the Java file if needed
- Check if already running (prevents duplicates)
- Save PID for easy management
- Redirect output to monitor.log
- Handle graceful shutdown

### Manual Background Execution

#### Using nohup
```bash
nohup java WebSphereAuditMonitor admin mypassword > monitor.log 2>&1 &
echo $! > .monitor.pid
```

To stop:
```bash
kill $(cat .monitor.pid)
```

#### Using screen
```bash
screen -dmS wsaudit java WebSphereAuditMonitor admin mypassword
# To reattach: screen -r wsaudit
# To detach: Ctrl+A then D
```

### As a systemd service

Create `/etc/systemd/system/wsaudit.service`:

```ini
[Unit]
Description=WebSphere Audit Monitor
After=network.target

[Service]
Type=simple
User=wasadmin
WorkingDirectory=/opt/wsaudit
ExecStart=/usr/bin/java WebSphereAuditMonitor admin mypassword
Restart=always
Environment="WAS_USER=admin"
Environment="WAS_PASS=mypassword"

[Install]
WantedBy=multi-user.target
```

Then:
```bash
sudo systemctl daemon-reload
sudo systemctl enable wsaudit
sudo systemctl start wsaudit
```

## Troubleshooting

### Program exits immediately
- Ensure you provided username and password as arguments
- Check that config.properties exists and is properly formatted
- Verify checkpoint.directory path exists and is accessible

### wsadmin.sh fails
- Verify wsadmin.path is correct
- Check username and password provided as arguments are valid
- Ensure WebSphere server is running
- Check wsadmin.sh has execute permissions

### No checkpoints found
- Verify checkpoint.directory path is correct
- Check that Delta-* directories exist
- Ensure proper read permissions on the directory

### Audit log not generated
- Check write permissions for audit.log.path
- Verify there are actual differences between before/after files

## Files Generated

- `config.properties` - Configuration file (created on first run)
- `.last_processed_timestamp` - Tracks last processed checkpoint timestamp
- `.monitor.pid` - Process ID of running monitor (when using scripts)
- `monitor.log` - Console output and errors (when running in background)
- `audit.log` - Audit log with all configuration changes
- `Delta-*.zip` - Temporary checkpoint zip files (automatically deleted after processing)

## Management Scripts

Three shell scripts are included for easy process management:

1. **start-monitor.sh** - Starts the monitor in background
   - Auto-compiles if needed
   - Prevents duplicate instances
   - Saves PID for management
   - Logs to monitor.log

2. **stop-monitor.sh** - Stops the running monitor
   - Graceful shutdown with fallback to force kill
   - Cleans up PID file

3. **status-monitor.sh** - Shows monitor status
   - Displays PID, CPU, memory usage
   - Shows last 10 log lines

## Notes

- The program runs continuously once started
- First run processes all existing checkpoints
- Subsequent runs only process new checkpoints
- All changes are logged in chronological order
- Binary files are skipped in comparison
- Large files may take time to process
