package examples.py4j_example;

import py4j.GatewayServer;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * Simple Java application that demonstrates how to:
 * 1. Start a Py4J gateway server
 * 2. Launch a Python process that connects to the gateway
 * 3. Call methods on a Python service
 */
public class SimpleJavaApp {

    private static final int GATEWAY_PORT = 25333;
    private static final String PYTHON_SCRIPT_PATH = "examples/py4j_example/simple_python_service.py";
    
    private Process pythonProcess;
    private Object pythonService;
    private GatewayServer gatewayServer;

    /**
     * Initialize the gateway server and start the Python process
     */
    public void initialize() {
        try {
            // Start the gateway server with this object as the entry point
            gatewayServer = new GatewayServer(this, GATEWAY_PORT);
            gatewayServer.start();
            System.out.println("Gateway Server Started on port " + GATEWAY_PORT);

            // Check if the Python script exists
            File scriptFile = new File(PYTHON_SCRIPT_PATH);
            if (!scriptFile.exists()) {
                System.err.println("Python script not found at: " + PYTHON_SCRIPT_PATH);
                return;
            }

            // Start the Python process
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "python3", PYTHON_SCRIPT_PATH, String.valueOf(GATEWAY_PORT));
            processBuilder.redirectErrorStream(true);
            pythonProcess = processBuilder.start();

            System.out.println("Python process started");
            
            // Wait for Python service to register
            waitForPythonServiceRegistration();
            
        } catch (Exception e) {
            System.err.println("Failed to initialize: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Wait for the Python service to register itself with this gateway
     */
    private void waitForPythonServiceRegistration() {
        final int MAX_WAIT_TIME_MS = 10000; // 10 seconds timeout
        final int CHECK_INTERVAL_MS = 500;  // Check every 500ms
        int waitTime = 0;
        
        while (pythonService == null && waitTime < MAX_WAIT_TIME_MS) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
                waitTime += CHECK_INTERVAL_MS;
                
                if (pythonService != null) {
                    System.out.println("Python service registered successfully after " + waitTime + "ms");
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while waiting for Python service registration");
                return;
            }
        }
        
        if (pythonService == null) {
            System.err.println("Python service registration timed out after " + MAX_WAIT_TIME_MS + "ms");
        }
    }

    /**
     * Register a Python service with this gateway
     * This method is called by the Python side to register its service
     * 
     * @param service The Python service to register
     */
    public void registerPythonService(Object service) {
        this.pythonService = service;
        System.out.println("Python service registered: " + service.getClass().getName());
    }

    /**
     * Call methods on the Python service
     */
    public void callPythonMethods() {
        if (pythonService == null) {
            System.err.println("Python service is not available");
            return;
        }
        
        try {
            // Call the add method
            Method addMethod = pythonService.getClass().getMethod("add", int.class, int.class);
            int sum = (int) addMethod.invoke(pythonService, 5, 7);
            System.out.println("Python add result: " + sum);
            
            // Call the multiply method
            Method multiplyMethod = pythonService.getClass().getMethod("multiply", int.class, int.class);
            int product = (int) multiplyMethod.invoke(pythonService, 6, 8);
            System.out.println("Python multiply result: " + product);
            
            // Call the get_greeting method
            Method greetingMethod = pythonService.getClass().getMethod("get_greeting", String.class);
            String greeting = (String) greetingMethod.invoke(pythonService, "Java User");
            System.out.println("Python greeting: " + greeting);
            
        } catch (Exception e) {
            System.err.println("Error calling Python methods: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Clean up resources
     */
    public void cleanup() {
        if (gatewayServer != null) {
            gatewayServer.shutdown();
            System.out.println("Gateway Server Stopped");
        }
        
        if (pythonProcess != null) {
            pythonProcess.destroy();
            System.out.println("Python process terminated");
        }
    }

    public static void main(String[] args) {
        SimpleJavaApp app = new SimpleJavaApp();
        
        try {
            // Initialize the gateway and Python process
            app.initialize();
            
            // Wait a bit to ensure Python service is registered
            Thread.sleep(2000);
            
            // Call Python methods
            app.callPythonMethods();
            
            // Keep the application running for a while
            System.out.println("Press Enter to exit...");
            System.in.read();
            
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Clean up resources
            app.cleanup();
        }
    }
}