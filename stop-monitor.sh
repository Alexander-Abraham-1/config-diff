#!/bin/bash

# WebSphere Audit Monitor Stop Script

PID_FILE=".monitor.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Monitor is not running (PID file not found)"
    exit 1
fi

PID=$(cat "$PID_FILE")

if ps -p $PID > /dev/null 2>&1; then
    echo "Stopping WebSphere Audit Monitor (PID: $PID)..."
    kill $PID
    
    # Wait for process to stop
    for i in {1..10}; do
        if ! ps -p $PID > /dev/null 2>&1; then
            echo "Monitor stopped successfully"
            rm -f "$PID_FILE"
            exit 0
        fi
        sleep 1
    done
    
    # Force kill if still running
    if ps -p $PID > /dev/null 2>&1; then
        echo "Process did not stop gracefully, forcing..."
        kill -9 $PID
        sleep 1
    fi
    
    if ! ps -p $PID > /dev/null 2>&1; then
        echo "Monitor stopped (forced)"
        rm -f "$PID_FILE"
    else
        echo "Failed to stop monitor"
        exit 1
    fi
else
    echo "Monitor is not running (PID $PID not found)"
    rm -f "$PID_FILE"
fi

# Made with Bob
