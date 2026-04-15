#!/bin/bash

# WebSphere Audit Monitor Startup Script
# Usage: ./start-monitor.sh [config.properties]

CONFIG=${1:-config.properties}

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
nohup java WebSphereAuditMonitor "$CONFIG" > monitor.log 2>&1 &
PID=$!

# Save PID
echo $PID > "$PID_FILE"

echo "Monitor started with PID: $PID"
echo "Config file: $CONFIG"
echo "Log file: monitor.log"
echo "To stop: ./stop-monitor.sh"
echo "To view logs: tail -f monitor.log"

# Made with Bob
