#!/bin/bash

# WebSphere Audit Monitor Stop Script

PID_FILE=".monitor.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Monitor is not running (PID file not found)"
    exit 1
fi

PID=$(cat "$PID_FILE")

# Check if process exists at all
if ! ps -p $PID > /dev/null 2>&1; then
    echo "Process $PID is not running. Cleaning up PID file..."
    rm -f "$PID_FILE"
    echo "PID file removed."
    exit 0
fi

# Check if process is a zombie
if ps -p $PID -o stat= 2>/dev/null | grep -q 'Z'; then
    echo "Process $PID is a zombie (defunct). Cleaning up..."
    rm -f "$PID_FILE"
    echo "PID file removed. The process is already terminated."
    exit 0
fi

echo "Stopping WebSphere Audit Monitor (PID: $PID)..."

# Try SIGTERM first
kill $PID 2>/dev/null

# Wait for process to stop (shorter wait)
for i in {1..5}; do
    if ! ps -p $PID > /dev/null 2>&1; then
        echo "Monitor stopped successfully"
        rm -f "$PID_FILE"
        exit 0
    fi
    sleep 1
done

# Try SIGKILL
echo "Process did not stop gracefully, using SIGKILL..."
kill -9 $PID 2>/dev/null
sleep 2

# Final check
if ! ps -p $PID > /dev/null 2>&1; then
    echo "Monitor stopped (forced)"
    rm -f "$PID_FILE"
    exit 0
fi

# If still running, it might be stuck - provide manual instructions
echo "WARNING: Process $PID may be stuck or unresponsive"
echo ""
echo "Please try these steps:"
echo "1. Manual kill: kill -9 $PID"
echo "2. Remove PID file: rm -f $PID_FILE"
echo "3. Check if it's a zombie: ps -p $PID -o stat="
echo "4. If zombie, it will be cleaned up automatically by the system"
echo ""
echo "After manual kill, run this to clean up:"
echo "  rm -f $PID_FILE"

exit 1
