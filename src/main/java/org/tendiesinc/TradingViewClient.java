package org.tendiesinc;

import io.github.bonigarcia.wdm.WebDriverManager;
import io.github.robzsaunders.robinstoolbox.Print;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public class TradingViewClient {
    private Print p = Print.getInstance();
    private boolean isConnected = false;
    private String authKey = null;
    private String sessionid_sign = null;
    private WebSocket webSocket;
    private CountDownLatch latch = new CountDownLatch(153);
    private String mozillaFilePath = System.getenv("APPDATA")+"\\Mozilla\\Firefox\\Profiles";
    private boolean manualAuthKeyAdded = true;
    public TradingViewClient() throws URISyntaxException {


    }

    private String generateSession(String prefix) {
        Random random = new Random();
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < 12; i++) {
            builder.append((char) ('a' + random.nextInt(26)));
        }
        return builder.toString();
    }

    private String prependHeader(String st) {
        return "~m~" + st.length() + "~m~" + st;
    }

    private void sendMessage(String function, Object[] parameters) {
        JSONObject message = new JSONObject();
        message.put("m", function);
        message.put("p", new JSONArray(parameters));

        String serializedMessage = message.toString();
        String formattedMessage = prependHeader(serializedMessage);
        p.log(formattedMessage);
        webSocket.sendText(formattedMessage, true);
    }

    public void connect() {
        getAuthDetails();
        HttpClient httpClient = HttpClient.newHttpClient();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .header("Origin", "https://data.tradingview.com")
                .connectTimeout(Duration.ofSeconds(20));

        webSocket = builder.buildAsync(URI.create("wss://prodata.tradingview.com/socket.io/websocket?from=chart%2FXOdycC2g%2F&date=2023_11_22-12_48&type=chart")
                , new WebSocket.Listener(){

                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                        p.log("Opened connection");
                        isConnected = true;
                    }

                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        String message = data.toString();
                        p.log("Received: "+message+" "+last);

                        if (message.contains("~m~~h~")){ // Tradingview sending a ping requiring a pong
                            p.log("Sending: "+message.split(" ")[0]);
                            webSocket.sendText(message.split(" ")[0], true);
                        }

                        if (message.contains("bad auth token")){
                            p.log("Bad Auth Token, go grab your token again!");
                            //p.toBoth("Bad auth token, trying to regenerate the tokens and then will try connecting again!");
                            //new Thread(()->{regenerateAuthKeys();}).start();
                            //sleepytime(15000); // 15 seconds is probably enough?
                            //connect(); // ok lets try again!
                        }

                        webSocket.request(1);
                        return null;
                    }

                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("WebSocket closed: " + statusCode + " " + reason);

                        return null;
                    }

                    public void onError(WebSocket webSocket, Throwable error) {
                        p.log("Error");
                        error.printStackTrace();
                    }
                }).join();


        while (!isConnected) {
            sleepytime(100);
        }
        p.log("Nice connected");
        performActions();
        try {
            latch.await(); //keeps this alive for testing. You'll likely remove this with your app keeping the program alive
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void sleepytime(int time){
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void performActions() {
        String chartSession = generateSession("cs_");
        String session = generateSession("qs_");
        sendMessage("set_auth_token", new Object[]{authKey});
        p.log("HEY");
        // This is setup to make a basic chart and stream back the live trade data.
        sendMessage("set_locale", new Object[]{"en","US"});
        sendMessage("chart_create_session", new Object[]{chartSession, ""});
        sendMessage("quote_create_session", new Object[]{session});
        sendMessage("quote_set_fields", new Object[]{session, "base-currency-logoid","ch","chp","currency-logoid","currency_code","currency_id","base_currency_id","current_session","description","exchange","format","fractional","is_tradable","language","local_description","listed_exchange","logoid","lp","lp_time","minmov","minmove2","original_name","pricescale","pro_name","short_name","type","typespecs","update_mode","volume","value_unit_id"});
        sendMessage("switch_timezone", new Object[]{chartSession, "America/Toronto"});
        sendMessage("resolve_symbol", new Object[]{chartSession,"sds_sym_1","={\"symbol\":\"CME_MINI:MNQ1!\",\"adjustment\":\"splits\"}"});
        sendMessage("create_series", new Object[]{chartSession,"sds_1","s1","sds_sym_1","5S",300,""});
        sendMessage("create_study", new Object[]{chartSession, "st1","sessions_1","sds_1","Sessions@tv-basicstudies-226"});
        sendMessage("quote_add_symbols", new Object[]{session, "CME_MINI:MNQ1!"});// BINANCE:DOGEUSDT
        sendMessage("quote_hibernate_all: ", new Object[]{session});
        sleepytime(1000);
    }

    private String getAuthDetails() {
        if (!manualAuthKeyAdded) {
            try {
                File deets = new File(Paths.get(mozillaFilePath, "TradingViewDetails.txt").toString());
                if (deets.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(deets));
                    for (String line; (line = reader.readLine()) != null; ) {
                        if (line.contains("authKey")) {
                            if (line.split(":::").length > 1) {
                                authKey = line.split(":::")[1]; //if you're having issues make sure that the sessionid_sign and auth from your cookie doesn't have any -'s in it. Change this regex to fit your key
                            }
                        }
                        if (line.contains("sessionid_sign")) {
                            if (line.split(":::").length > 1) {
                                sessionid_sign = line.split(":::")[1];
                            }
                        }
                    }
                }
            } catch (IOException e) {
                System.out.println(e);
            }
            // We store both locally so if one is null lets just grab it
            if (sessionid_sign == null) {
                sessionid_sign = getSessionKey();
            }
            if (authKey == null) {
                authKey = generateAuthToken();
            }
        }
        else {
            try {
                // change this to whereever you put your Authkey. I found mine by going to the network tab in my browser and sniffing the traffic on the websocket when I refresh the page.
                // I use the resources folder in the project but if I am compiling this into a JAR I'll use another destination or have a field in the app I'm using where I can paste it in.
                InputStream data = this.getClass().getResourceAsStream("/TradingViewDetails");
                if (data != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(data));
                    for (String line; (line = reader.readLine()) != null; ) {
                        if (line.contains("authKey")) {
                            if (line.split(":::").length > 1) {
                                authKey = line.split(":::")[1];
                            }
                        }
                    }
                } else {
                    p.log("Create a resource to access the authKey");
                }
            } catch (IOException e) {
                System.out.println(e);
            }
        }
        writeAuthInfo();
        p.log(authKey);
        return authKey;
    }

    private String getSessionKey(){
        // Using firefox since that's what I use locally but this can be configured to your browser.
        WebDriverManager.firefoxdriver().setup();
        FirefoxOptions options = new FirefoxOptions();

        // Load existing Firefox profile to find cookies. Should be in your local AppData\Roaming
        FirefoxProfile profile = new FirefoxProfile(new File(mozillaFilePath+"\\0trm10vb.default-release")); // This will likely need to be updated to fit your installation
        options.setProfile(profile);
        WebDriver driver = new FirefoxDriver(options);
        driver.get("https://www.tradingview.com/"); // Navigate to TradingView

        // Wait for the page to load and cookies to be applied. This may launch firefox, don't be alarmed!
        sleepytime(10000); // Adjust this timing as needed.

        Set<Cookie> allCookies = driver.manage().getCookies();
        // Iterate through cookies to find the authentication cookie
        for (Cookie cookie : allCookies) {
            if (cookie.getName().equals("sessionid_sign")) { // Replace with actual cookie name
                sessionid_sign = cookie.getValue();
                break;
            }
        }
        driver.quit();
        return sessionid_sign;
    }

    public String generateAuthToken(){
        // This doesn't work, I couldn't figure out how to get / generate the auth token automatically from the browser without logging in which triggers a captcha check

        HttpResponse<String> response = null;
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.tradingview.com/quote_token/"))
                    .header("Cookie", sessionid_sign)
                    .build();

            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }

        p.log(response.body());
        return response.body();
    }

    private void regenerateAuthKeys(){
        sessionid_sign = getSessionKey();
        authKey = generateAuthToken();
        writeAuthInfo();
    }

    private void writeAuthInfo(){
        String fileName = "TradingViewDetails.txt";
        try (FileWriter fileWriter = new FileWriter(Paths.get(mozillaFilePath, fileName).toString())) {
            fileWriter.write("sessionid_sign:::"+sessionid_sign+"\nauthKey:::"+authKey);
        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Error occurred while writing to the key file.");
        }
    }

    public static void main(String[] args) {
        try {
            TradingViewClient client = new TradingViewClient();
            client.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

}

