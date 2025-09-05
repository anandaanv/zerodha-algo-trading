#!/bin/bash

# Script to compile and run the Java-Python integration example

echo "=== Java-Python Integration Example ==="
echo ""

# Check if Py4J is installed for Python
echo "Checking Python Py4J installation..."
if python3 -c "import py4j" 2>/dev/null; then
    echo "✓ Py4J is installed for Python"
else
    echo "✗ Py4J is not installed for Python. Please install it with: pip install py4j"
    exit 1
fi

# Find the Py4J JAR file
echo "Looking for Py4J JAR file..."
PY4J_PATH=$(python3 -c "import py4j, os; print(os.path.dirname(py4j.__file__))")
PY4J_JAR=$(find "$PY4J_PATH" -name "py4j*.jar" | head -1)

if [ -z "$PY4J_JAR" ]; then
    echo "✗ Could not find Py4J JAR file. Please specify the path manually in this script."
    exit 1
else
    echo "✓ Found Py4J JAR at: $PY4J_JAR"
fi

# Create the examples directory if it doesn't exist
mkdir -p examples/py4j_example/build

# Compile the Java code
echo ""
echo "Compiling Java code..."
javac -cp ".:$PY4J_JAR" -d examples/py4j_example/build examples/py4j_example/SimpleJavaApp.java

if [ $? -ne 0 ]; then
    echo "✗ Compilation failed"
    exit 1
else
    echo "✓ Compilation successful"
fi

# Make the Python script executable
chmod +x examples/py4j_example/simple_python_service.py

# Run the example
echo ""
echo "Running the example..."
echo "Press Ctrl+C to stop the example after seeing the results"
echo ""

# Run the Java application
java -cp "examples/py4j_example/build:$PY4J_JAR" examples.py4j_example.SimpleJavaApp

echo ""
echo "Example completed"