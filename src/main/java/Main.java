import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    private static final int PORT = 4221;
    private static final int THREAD_POOL_SIZE = 25;

    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);


        try (ServerSocket serverSocket = new ServerSocket(PORT)){
            serverSocket.setReuseAddress(true);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executorService.execute(new ClientHandler(clientSocket));
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }

    }
}