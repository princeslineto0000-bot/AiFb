import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

public class GeminiServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    private static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");
    private static final String API_KEY = System.getenv("GEMINI_API_KEY");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/analyze", GeminiServer::analyze);
        server.createContext("/", GeminiServer::staticFile);
        server.setExecutor(null);
        server.start();
        System.out.println("Web app listening on port " + PORT);
    }

    private static void analyze(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Use POST for audio analysis.\"}");
            return;
        }
        if (API_KEY == null || API_KEY.isBlank()) {
            sendJson(exchange, 500, "{\"error\":\"Server configuration is missing GEMINI_API_KEY.\"}");
            return;
        }

        try {
            byte[] requestBody = readLimited(exchange, 20 * 1024 * 1024);
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            AudioPart audio = extractAudio(requestBody, contentType);
            String mimeType = audio.mimeType.toLowerCase(Locale.ROOT);
            if (!mimeType.startsWith("audio/")) {
                sendJson(exchange, 400, "{\"error\":\"The uploaded file is not recognized as audio.\"}");
                return;
            }

            String prompt = "Bạn là gia sư luyện nói tiếng Anh cho người Việt. Hãy nghe kỹ đoạn ghi âm. "
                    + "Trả lời bằng JSON hợp lệ với đúng ba trường string: transcript (chép chính xác lời người nói và giữ nguyên ngôn ngữ), "
                    + "correctedText (nếu người nói dùng tiếng Anh, viết lại câu tiếng Anh tự nhiên và đúng ngữ pháp nhưng giữ nguyên ý; "
                    + "nếu người nói dùng tiếng Việt, dịch ý đó thành một câu tiếng Anh tự nhiên để người học luyện tập), "
                    + "feedback (nhận xét ngắn gọn bằng tiếng Việt về ngữ pháp, cách dùng từ hoặc độ tự nhiên, kèm một gợi ý luyện tập cụ thể. "
                    + "Chỉ nhận xét phát âm khi âm thanh đủ rõ; không suy đoán lỗi phát âm nếu không chắc). "
                    + "Không thêm markdown hay nội dung ngoài JSON. Nếu không nghe rõ, nói rõ điều đó trong feedback.";
            String geminiBody = "{\"contents\":[{\"parts\":["
                    + "{\"text\":" + quoteJson(prompt) + "},"
                    + "{\"inline_data\":{\"mime_type\":" + quoteJson(mimeType)
                    + ",\"data\":" + quoteJson(Base64.getEncoder().encodeToString(audio.bytes)) + "}}]}],"
                    + "\"generationConfig\":{\"responseMimeType\":\"application/json\"}}";

            URI uri = URI.create("https://generativelanguage.googleapis.com/v1beta/models/"
                    + MODEL + ":generateContent?key=" + API_KEY);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(geminiBody))
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("Gemini API returned HTTP " + response.statusCode() + ": " + response.body());
                sendJson(exchange, 502, "{\"error\":\"Gemini API request failed. Check the server key, model, and audio format.\"}");
                return;
            }

            String modelText = findJsonStringValue(response.body(), "text");
            if (modelText == null) {
                throw new IOException("Gemini returned no text content.");
            }
            String result = normalizeModelJson(modelText);
            sendJson(exchange, 200, result);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":" + quoteJson(e.getMessage()) + "}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 500, "{\"error\":\"The Gemini request was interrupted.\"}");
        } catch (Exception e) {
            System.err.println("Audio analysis failed: " + e.getMessage());
            sendJson(exchange, 502, "{\"error\":\"Audio analysis failed. Check the server configuration and Gemini response.\"}");
        }
    }

    private static byte[] readLimited(HttpExchange exchange, int limit) throws IOException {
        try (var input = exchange.getRequestBody(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new IllegalArgumentException("Audio upload exceeds the 20 MB limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static AudioPart extractAudio(byte[] body, String contentType) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            throw new IllegalArgumentException("Expected multipart/form-data with an audio field.");
        }
        String boundary = null;
        for (String parameter : contentType.split(";")) {
            String trimmed = parameter.trim();
            if (trimmed.startsWith("boundary=")) boundary = trimmed.substring("boundary=".length()).replace("\"", "");
        }
        if (boundary == null || boundary.isBlank()) throw new IllegalArgumentException("Multipart boundary is missing.");

        String raw = new String(body, StandardCharsets.ISO_8859_1);
        String[] parts = raw.split(java.util.regex.Pattern.quote("--" + boundary));
        for (String part : parts) {
            int headerEnd = part.indexOf("\r\n\r\n");
            if (headerEnd < 0 || !part.substring(0, headerEnd).contains("name=\"audio\"")) continue;
            String headers = part.substring(0, headerEnd);
            int dataStart = headerEnd + 4;
            int dataEnd = part.endsWith("\r\n") ? part.length() - 2 : part.length();
            String partType = "audio/webm";
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-type:")) {
                    partType = line.substring(line.indexOf(':') + 1).trim().split(";")[0];
                }
            }
            byte[] bytes = part.substring(dataStart, dataEnd).getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length == 0) throw new IllegalArgumentException("The audio recording is empty.");
            return new AudioPart(bytes, partType);
        }
        throw new IllegalArgumentException("Multipart request does not contain the audio field.");
    }

    private static String normalizeModelJson(String text) throws IOException {
        String value = text.trim();
        if (value.startsWith("```") && value.endsWith("```")) {
            value = value.substring(value.indexOf('\n') + 1, value.length() - 3).trim();
        }
        String transcript = findJsonStringValue(value, "transcript");
        String corrected = findJsonStringValue(value, "correctedText");
        String feedback = findJsonStringValue(value, "feedback");
        if (transcript == null && corrected == null && feedback == null) {
            throw new IOException("Gemini response did not contain the expected JSON fields.");
        }
        return "{\"transcript\":" + quoteJson(transcript == null ? "" : transcript)
                + ",\"correctedText\":" + quoteJson(corrected == null ? "" : corrected)
                + ",\"feedback\":" + quoteJson(feedback == null ? "" : feedback) + "}";
    }

    private static String findJsonStringValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyAt = json.indexOf(marker);
        if (keyAt < 0) return null;
        int colon = json.indexOf(':', keyAt + marker.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 >= json.length()) return null;
                        value.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    default -> value.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }

    private static String quoteJson(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void staticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        if (requestPath.equals("/")) requestPath = "/index.html";
        if (!requestPath.equals("/index.html") && !requestPath.equals("/styles.css") && !requestPath.equals("/script.js")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path file = root.resolve(requestPath.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class AudioPart {
        final byte[] bytes;
        final String mimeType;

        AudioPart(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType;
        }
    }
}
