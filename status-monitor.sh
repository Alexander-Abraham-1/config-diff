#!/bin/bash

# WebSphere Audit Monitor Status Script

PID_FILE=".monitor.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Monitor is NOT running (PID file not found)"
    exit 1
fi

PID=$(cat "$PID_FILE")

if ps -p $PID > /dev/null 2>&1; then
    echo "Monitor is RUNNING"
    echo "PID: $PID"
    echo "Started: $(ps -p $PID -o lstart=)"
    echo "CPU: $(ps -p $PID -o %cpu=)%"
    echo "Memory: $(ps -p $PID -o %mem=)%"
    echo ""
    echo "Log file: monitor.log"
    echo "Last 10 lines of log:"
    echo "----------------------------------------"
    tail -10 monitor.log 2>/dev/null || echo "Log file not found"
else
    echo "Monitor is NOT running (PID $PID not found)"
    echo "Removing stale PID file..."
    rm -f "$PID_FILE"
    exit 1
fi

# Made with Bob
