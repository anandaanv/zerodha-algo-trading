#!/usr/bin/env python
# -*- coding: utf-8 -*-

"""
Simple Python Service Example

This example demonstrates how to:
1. Connect to a Java gateway server
2. Register a Python service with the Java gateway
3. Implement methods that can be called from Java
"""

import sys
from py4j.java_gateway import JavaGateway, GatewayParameters, CallbackServerParameters

class SimplePythonService:
    """
    A simple Python service that can be called from Java
    """
    
    def add(self, a, b):
        """Add two numbers and return the result"""
        print(f"Python service: Adding {a} + {b}")
        return a + b
    
    def multiply(self, a, b):
        """Multiply two numbers and return the result"""
        print(f"Python service: Multiplying {a} * {b}")
        return a * b
    
    def get_greeting(self, name):
        """Return a greeting message"""
        message = f"Hello, {name}! This message is from Python."
        print(f"Python service: Generating greeting for {name}")
        return message

def connect_to_gateway(port):
    """Connect to the Java gateway server"""
    print(f"Connecting to Java gateway on port {port}")
    
    # Create a connection to the Java gateway
    # The GatewayParameters specify the port to connect to
    # The CallbackServerParameters enable Java to call Python methods
    gateway = JavaGateway(
        gateway_parameters=GatewayParameters(port=port),
        callback_server_parameters=CallbackServerParameters(port=0)
    )
    
    print(f"Connected to Java gateway")
    return gateway

if __name__ == "__main__":
    # Check if port is provided as command line argument
    if len(sys.argv) < 2:
        print("Usage: python simple_python_service.py <gateway_port>")
        sys.exit(1)
    
    # Get the port from command line arguments
    port = int(sys.argv[1])
    
    # Connect to the Java gateway
    gateway = connect_to_gateway(port)
    
    # Create an instance of our service
    service = SimplePythonService()
    
    # Register the service with the Java gateway
    # The entry_point is the Java object that started the gateway
    # We call its registerPythonService method to register our service
    gateway.entry_point.registerPythonService(service)
    print("Python service registered with Java gateway")
    
    # Keep the process running to handle requests from Java
    try:
        print("Python service is running. Press Ctrl+C to exit.")
        while True:
            import time
            time.sleep(1)
    except KeyboardInterrupt:
        print("Shutting down Python service")
        gateway.shutdown()