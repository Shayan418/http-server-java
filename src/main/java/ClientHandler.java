import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.zip.GZIPOutputStream;

public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    long thirtySecondsInNanos = 5_000_000_000L;
    int MAX_REQUESTS = 1000;

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }


    @Override
    public void run() {
        try {
            clientSocket.setKeepAlive(true);
            System.out.println("accepted new connection");

            HashMap<String, String> headers = new HashMap<>();
            long startTime = System.nanoTime();
            int requestsCount = 0;
            boolean closeConnection = false;

            while (!clientSocket.isClosed()) {
                closeConnection = false;
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));

                if (!reader.ready()) {
                    if (canClose(headers, System.nanoTime() - startTime, requestsCount))
                        clientSocket.close();
                    else
                        Thread.sleep(100);
                    continue;
                }
                ++requestsCount;
                startTime = System.nanoTime();
                OutputStream writer = clientSocket.getOutputStream();
                System.out.println("reading from existing connection");

                String line;
                String resourceFound = "";
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    System.out.println("Received: " + line);
                    headers.put(line.split(" ")[0], line);
                }

                char[] cbuf = new char[1024];
                if (headers.containsKey("POST")){
                    reader.read(cbuf, 0, Integer.parseInt(headers.get("Content-Length:").split(": ")[1]));
                }


                String acceptedEncoding = "";
                HashSet<String> supportedEncodings = new HashSet<>();
                supportedEncodings.add("gzip");
                if (headers.containsKey("Accept-Encoding:")) {
                    String clientEncodings = headers.get("Accept-Encoding:").split(": ")[1];
                    String[] encodings = clientEncodings.split(",");
                    for (String encoding : encodings) {
                        if (supportedEncodings.contains(encoding.trim())) {
                            acceptedEncoding = encoding.trim();
                            break;
                        }
                    }
                }


                if (canClose(headers, System.nanoTime() - startTime, requestsCount)){
                    closeConnection = true;
                }

                String echoResponse = "";
                if (headers.containsKey("GET")) {
                    String[] startLine = headers.get("GET").split(" ");
                    String[] urlParts = startLine[1].split("/");
                    if (startLine[1].equals("/")) {
                        resourceFound = "text/plain";
                    }

                    if (urlParts.length == 3 && urlParts[1].equals("echo")) {
                        resourceFound = "text/plain";
                        echoResponse = urlParts[2];
                    } else if (urlParts.length == 2 && urlParts[1].equalsIgnoreCase("User-Agent")) {
                        resourceFound = "text/plain";
                        echoResponse = headers.get("User-Agent:").split(": ")[1];
                    }
                    if (urlParts.length == 3 && urlParts[1].equals("files")) {
                        echoResponse = urlParts[2];
                        if (Files.exists(Path.of("/tmp/data/codecrafters.io/http-server-tester/" + echoResponse))){
                            resourceFound = "application/octet-stream";
                        }
                    }

                    String response = "";
                    switch (resourceFound) {
                        case "text/plain":

                            if (!acceptedEncoding.isEmpty()) {
                                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                                try (GZIPOutputStream gzipOs = new GZIPOutputStream(byteStream)) {
                                    gzipOs.write(echoResponse.getBytes(StandardCharsets.UTF_8));
                                }
                                byte[] responseBodyBytes = byteStream.toByteArray();
                                response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain\r\n" +
                                        "Content-Encoding: " + acceptedEncoding + "\r\n" +
                                        "Content-Length: " + responseBodyBytes.length + "\r\n";
                                        if (closeConnection) {
                                            response += "Connection: close\r\n";
                                        }
                                        response += "\r\n";
                                writer.write(response.getBytes());
                                writer.write(responseBodyBytes);
                            } else {
                                response = "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: text/plain\r\n" +
                                        "Content-Length: " + echoResponse.length() + "\r\n";
                                if (closeConnection) {
                                    response += "Connection: close\r\n";
                                }
                                response += "\r\n";
                                writer.write(response.getBytes());
                                writer.write(echoResponse.getBytes());
                            }

                            break;

                        case "application/octet-stream":
                            File file = new File("/tmp/data/codecrafters.io/http-server-tester/" + echoResponse);
                            response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/octet-stream\r\n";
                            if (!acceptedEncoding.isEmpty()) {
                                response = response + "Content-Encoding: " + acceptedEncoding + "\r\n";
                            }
                             response = response + "Content-Length: " + file.length() + "\r\n";
                            if (closeConnection) {
                                response += "Connection: close\r\n";
                            }
                            response += "\r\n";

                            writer.write(response.getBytes());
                            byte[] fileBytes = Files.readAllBytes(file.toPath());
                            writer.write(fileBytes);
                            break;
                        default:
                            writer.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                    }
                }

                if (headers.containsKey("POST")){
                    boolean fileCreated = false;
                    String[] startLine = headers.get("POST").split(" ");
                    String[] urlParts = startLine[1].split("/");

                    if (urlParts.length == 3 && urlParts[1].equals("files")) {
                        String filename = urlParts[2];
                        File file = new File("/tmp/data/codecrafters.io/http-server-tester/" + filename );
                        file.createNewFile();
                        PrintWriter fileWriter = new PrintWriter(new FileOutputStream(file), true);
                        fileWriter.write(cbuf, 0, Integer.parseInt(headers.get("Content-Length:").split(": ")[1]));
                        fileWriter.close();
                        fileCreated = true;
                    }

                    String response = "";
                    if(fileCreated){
                        response = "HTTP/1.1 201 Created\r\n";
                        if (!acceptedEncoding.isEmpty()) {
                            response = response + "Content-Encoding: " + acceptedEncoding + "\r\n";
                        }
                        if (closeConnection) {
                            response += "Connection: close\r\n";
                        }
                        response += "\r\n";
                        writer.write(response.getBytes());
                    }
                }

                if (closeConnection) {
                    clientSocket.close();
                }

                headers.clear();
            }

        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }


    public boolean canClose(HashMap<String, String> headers, long elapsedTime, int requestsCount) {
        if (headers != null && "Connection: close".equals(headers.get("Connection:"))) {
            return true;
        }

        if (elapsedTime >= thirtySecondsInNanos) {
            return true;
        }

        return requestsCount > MAX_REQUESTS;
    }
}
