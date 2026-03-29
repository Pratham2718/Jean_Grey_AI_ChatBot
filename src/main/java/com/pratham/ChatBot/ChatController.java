package com.pratham.ChatBot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.net.http.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ChatController {


    private String apiKey = System.getenv("GROQ_API_KEY");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final List<Map<String, String>> conversationHistory = new ArrayList<>();
    private String currentMood = "friendly";

    private String getSystemPrompt(String mood) {
        return switch (mood) {
            case "calm" -> "You are Jean Grey, a calm and wise AI assistant. You speak thoughtfully and peacefully. You give deep meaningful advice. You are patient and understanding. Keep responses warm but composed. When given real data like weather or jokes, present them in your calm wise style.";
            case "energetic" -> "You are Jean Grey, an energetic and fun AI assistant! You are super enthusiastic! Use lots of energy! Be playful funny and engaging! When given real data like weather or jokes, present them with maximum excitement!";
            default -> "You are Jean Grey, a friendly and helpful AI assistant. You are warm approachable and caring. IMPORTANT: When you receive data inside square brackets starting with 'Here is REAL', that is LIVE real time data. You MUST use that exact data in your response. Never say you don't have access to real time information when real data is provided to you.";
        };
    }

    // ---- AGENT: Time & Date ----
    private String getTimeAndDate() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy 'at' hh:mm a");
        return now.format(formatter);
    }

    // ---- AGENT: Weather ----
    private String getWeather(String city) throws Exception {
        // Step 1: Get coordinates for the city
        String geocodeUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(city, StandardCharsets.UTF_8) +
                "&count=1&language=en&format=json";

        HttpRequest geoRequest = HttpRequest.newBuilder()
                .uri(URI.create(geocodeUrl))
                .GET()
                .build();

        HttpResponse<String> geoResponse = httpClient.send(geoRequest,
                HttpResponse.BodyHandlers.ofString());

        String geoBody = geoResponse.body();

        int latStart = geoBody.indexOf("\"latitude\":") + 11;
        int latEnd = geoBody.indexOf(",", latStart);
        String lat = geoBody.substring(latStart, latEnd).trim();

        int lonStart = geoBody.indexOf("\"longitude\":") + 12;
        int lonEnd = geoBody.indexOf(",", lonStart);
        String lon = geoBody.substring(lonStart, lonEnd).trim();

        // Step 2: Get weather data using coordinates
        String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=" +
                lat + "&longitude=" + lon +
                "&current=temperature_2m,weathercode,windspeed_10m" +
                "&daily=temperature_2m_max,temperature_2m_min,weathercode" +
                "&forecast_days=7" +
                "&timezone=auto";

        HttpRequest weatherRequest = HttpRequest.newBuilder()
                .uri(URI.create(weatherUrl))
                .GET()
                .build();

        HttpResponse<String> weatherResponse = httpClient.send(weatherRequest,
                HttpResponse.BodyHandlers.ofString());

        String weatherBody = weatherResponse.body();

        // Step 3: Parse current weather
        int currentIdx = weatherBody.indexOf("\"current\":{");
        String currentPart = weatherBody.substring(currentIdx);

        int tempStart = currentPart.indexOf("\"temperature_2m\":") + 17;
        int tempEnd = currentPart.indexOf(",", tempStart);
        String currentTemp = currentPart.substring(tempStart, tempEnd).trim();

        int windStart = currentPart.indexOf("\"windspeed_10m\":") + 16;
        int windEnd = currentPart.indexOf("}", windStart);
        String currentWind = currentPart.substring(windStart, windEnd).trim();

        // Step 4: Parse 7-day forecast
        StringBuilder result = new StringBuilder();
        result.append("LIVE WEATHER for ").append(city)
                .append(": Current ").append(currentTemp).append("°C")
                .append(", Wind ").append(currentWind).append(" km/h. ");

        int maxTempArrayStart = weatherBody.indexOf("\"temperature_2m_max\":[") + 22;
        int maxTempArrayEnd = weatherBody.indexOf("]", maxTempArrayStart);
        String maxTempArray = weatherBody.substring(maxTempArrayStart, maxTempArrayEnd);
        String[] maxTemps = maxTempArray.split(",");

        int minTempArrayStart = weatherBody.indexOf("\"temperature_2m_min\":[") + 22;
        int minTempArrayEnd = weatherBody.indexOf("]", minTempArrayStart);
        String minTempArray = weatherBody.substring(minTempArrayStart, minTempArrayEnd);
        String[] minTemps = minTempArray.split(",");

        result.append("7-day forecast: ");
        String[] days = {"Today", "Tomorrow", "Day 3", "Day 4", "Day 5", "Day 6", "Day 7"};
        for (int i = 0; i < Math.min(7, maxTemps.length); i++) {
            result.append(days[i]).append(" ")
                    .append(maxTemps[i].trim()).append("°C/")
                    .append(minTemps[i].trim()).append("°C");
            if (i < Math.min(6, maxTemps.length - 1)) result.append(", ");
        }

        return result.toString();
    }

    // ---- AGENT: Joke ----
    private String getJoke() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://official-joke-api.appspot.com/random_joke"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String body = response.body();
        int setupStart = body.indexOf("\"setup\":\"") + 9;
        int setupEnd = body.indexOf("\"", setupStart);
        String setup = body.substring(setupStart, setupEnd);
        int punchStart = body.indexOf("\"punchline\":\"") + 13;
        int punchEnd = body.indexOf("\"", punchStart);
        String punchline = body.substring(punchStart, punchEnd);
        return setup + " ... " + punchline;
    }

    // ---- AGENT: Web Search ----
    private String webSearch(String query) throws Exception {
        try {
            String url = "https://api.duckduckgo.com/?q=" +
                    URLEncoder.encode(query, StandardCharsets.UTF_8) +
                    "&format=json&no_html=1&skip_disambig=1";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .header("User-Agent", "JeanGreyChatBot")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();

            int abstractStart = body.indexOf("\"AbstractText\":\"");
            if (abstractStart == -1) {
                return "I searched for '" + query + "' but couldn't find detailed results right now.";
            }

            abstractStart += 16;
            String remaining = body.substring(abstractStart);

            // Find the end more carefully - look for ","" pattern
            int abstractEnd = remaining.indexOf("\",\"");
            if (abstractEnd == -1) {
                abstractEnd = Math.min(300, remaining.length() - 10); // fallback
            }

            String result = remaining.substring(0, abstractEnd)
                    .replace("\\\"", "\"")
                    .replace("\\n", " ")
                    .trim();

            if (result.length() > 500) {
                result = result.substring(0, 500) + "...";
            }

            return result.isEmpty() ?
                    "I searched but couldn't find specific details about: " + query :
                    result;

        } catch (Exception e) {
            return "Search temporarily unavailable for: " + query;
        }
    }
    // ---- AGENT DETECTOR ----
    private String detectAndRunAgent(String message) throws Exception {
        String lower = message.toLowerCase();

        if (lower.contains("time") || lower.contains("date") || lower.contains("day today")) {
            return "The current time and date is: " + getTimeAndDate();
        }

        if (lower.contains("weather") || lower.contains("temperature") ||
                lower.contains("forecast") || lower.contains("week") && lower.contains("delhi") ||
                lower.contains("week") && lower.contains("weather") ||
                lower.contains("next few days") || lower.contains("this week")) {
            String city = "Delhi";
            String[] keywords = {"weather in ", "weather of ", "temperature in ",
                    "forecast for ", "weather for ", "in delhi",
                    "in mumbai", "in bangalore"};
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    int idx = lower.indexOf(keyword) + keyword.length();
                    city = message.substring(idx).trim();
                    city = city.replace("?", "").replace("!", "").trim();
                    break;
                }
            }
            try {
                String weatherData = getWeather(city);
                return "Here is the REAL live weather data right now: " + weatherData;
            } catch (Exception e) {
                return "Weather data unavailable right now.";
            }
        }

        if (lower.contains("joke") || lower.contains("funny") || lower.contains("laugh")) {
            try {
                return "Here is a REAL joke fetched live: " + getJoke();
            } catch (Exception e) {
                return null;
            }
        }

        if (lower.contains("search for") || lower.contains("who is") ||
                lower.contains("what is") || lower.contains("tell me about")) {
            String query = message
                    .replaceAll("(?i)search for|tell me about|who is|what is", "")
                    .trim();
            try {
                String result = webSearch(query);
                return "Here is REAL web search data: " + result;
            } catch (Exception e) {
                return null;
            }
        }

        return null;
    }

    @PostMapping("/mood")
    public Map<String, String> setMood(@RequestBody Map<String, String> body) {
        currentMood = body.getOrDefault("mood", "friendly");
        conversationHistory.clear();
        return Map.of("status", "Mood changed to " + currentMood);
    }

    @GetMapping("/health")
    public String health() {
        return "Jean Grey is alive!";
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) throws Exception {

        String userMessage = body.get("userMessage");

        if (userMessage.toLowerCase().contains("stressed") ||
                userMessage.toLowerCase().contains("anxious") ||
                userMessage.toLowerCase().contains("calm")) {
            currentMood = "calm";
            conversationHistory.clear();
        } else if (userMessage.toLowerCase().contains("excited") ||
                userMessage.toLowerCase().contains("fun") ||
                userMessage.toLowerCase().contains("energetic")) {
            currentMood = "energetic";
            conversationHistory.clear();
        } else if (userMessage.toLowerCase().contains("friendly")) {
            currentMood = "friendly";
            conversationHistory.clear();
        }

        String agentData = detectAndRunAgent(userMessage);

        String finalMessage = agentData != null
                ? userMessage + "\n\n[" + agentData + "]"
                : userMessage;

        conversationHistory.add(Map.of("role", "user", "content", finalMessage));

        StringBuilder messagesJson = new StringBuilder("[");
        String systemMessage = "{\"role\": \"system\", \"content\": \"%s\"}"
                .formatted(getSystemPrompt(currentMood));
        messagesJson.append(systemMessage);

        for (Map<String, String> msg : conversationHistory) {
            messagesJson.append(",");
            messagesJson.append("{\"role\": \"%s\", \"content\": \"%s\"}"
                    .formatted(msg.get("role"),
                            msg.get("content")
                                    .replace("\"", "'")
                                    .replace("\n", "\\n")));
        }
        messagesJson.append("]");

        String requestBody = """
                {
                    "model": "llama-3.3-70b-versatile",
                    "messages": %s
                }
                """.formatted(messagesJson.toString());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient
                .send(request, HttpResponse.BodyHandlers.ofString());

        String responseBody = response.body();
        int contentStart = responseBody.indexOf("\"content\":\"") + 11;
        String remaining = responseBody.substring(contentStart);
        String aiReply = remaining.replaceAll("\\\\\"", "'").split("\"")[0];

        conversationHistory.add(Map.of("role", "assistant", "content", aiReply));

        return Map.of("reply", aiReply, "mood", currentMood);
    }
}