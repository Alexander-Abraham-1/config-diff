#!/bin/bash

# WebSphere Audit Monitor Startup Script
# Usage: ./start-monitor.sh <username> <password> [config.properties]

if [ $# -lt 2 ]; then
    echo "Usage: $0 <username> <password> [config.properties]"
    echo ""
    echo "Example:"
    echo "  $0 admin mypassword"
    echo "  $0 admin mypassword /path/to/config.properties"
    exit 1
fi

USERNAME=$1
PASSWORD=$2
CONFIG=${3:-config.properties}

# Check if Java class exists
if [ ! -f "WebSphereAuditMonitor.class" ]; then
    echo "WebSphereAuditMonitor.class not found. Compiling..."
    javac WebSphereAuditMonitor.java
    if [ $? -ne 0 ]; then
        echo "Compilation failed!"
        exit 1
    fi
    echo "Compilation successful."
fi

# Check if already running
PID_FILE=".monitor.pid"
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if ps -p $OLD_PID > /dev/null 2>&1; then
        echo "Monitor is already running with PID: $OLD_PID"
        echo "To stop it, run: ./stop-monitor.sh"
        exit 1
    else
        echo "Removing stale PID file..."
        rm -f "$PID_FILE"
    fi
fi

# Start the monitor in background
echo "Starting WebSphere Audit Monitor..."
nohup ../WebSphere905/AppServer/java/8.0/bin/java WebSphereAuditMonitor "$USERNAME" "$PASSWORD" "$CONFIG" > monitor.log 2>&1 &
PID=$!

# Save PID
echo $PID > "$PID_FILE"

echo "Monitor started with PID: $PID"
echo "Log file: monitor.log"
echo "To stop: ./stop-monitor.sh"
echo "To view logs: tail -f monitor.log"

# Made with Bob
