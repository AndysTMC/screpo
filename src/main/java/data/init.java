package data;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.*;
import org.openqa.selenium.support.ui.*;

import io.github.bonigarcia.wdm.WebDriverManager;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.*;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;

public class init {

    private static final String SERVER_URL = "https://6t6923cp-5000.inc1.devtunnels.ms";
    private static final int THREAD_COUNT = 5;

    public static void main(String[] args) {
        runScraperWithRestart();
    }

    private static void runScraperWithRestart() {
        while (true) {
            try {
                System.out.println("🚀 Distributed scraper starting...");
                runScraper();
                break;
            } catch (Exception e) {
                System.out.println("💥 Crash detected. Restarting in 5 seconds...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static void runScraper() throws Exception {
        WebDriverManager.chromedriver().setup();
        String clientId = InetAddress.getLocalHost().getHostName() + "-" +
                UUID.randomUUID().toString().substring(0, 4);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> workerLoop(clientId));
        }
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    private static void workerLoop(String clientId) {
        while (true) {
            try {
                JSONArray tasks = getTaskBatchFromServer(clientId);
                if (tasks.length() == 0) {
                    System.out.println("🎉 No tasks left for this worker.");
                    return;
                }

                for (int i = 0; i < tasks.length(); i++) {
                    JSONObject task = tasks.getJSONObject(i);
                    int rowNumber = task.getInt("row_number");
                    String companyUrl = task.getString("company_url");

                    try {
                        JSONObject companyJson = scrapeCompany(companyUrl);
                        submitDataToServer(rowNumber, companyJson);
                        System.out.println("✅ Row " + rowNumber + " done");
                    } catch (Exception e) {
                        System.out.println("❌ Failed row " + rowNumber);
                    }
                }

            } catch (Exception e) {
                System.out.println("⚠ Worker error, retrying...");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    private static JSONObject scrapeCompany(String companyUrl) throws Exception {

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--no-sandbox", "--incognito");

        WebDriver driver = new ChromeDriver(options);
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));

        try {
            driver.get(companyUrl);
            closePopupIfPresent(driver);

            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("window.scrollTo(0, document.body.scrollHeight/2);");
            Thread.sleep(1500);

            try {
                WebElement showMoreBtn = driver.findElement(By.cssSelector("button.show-more-less__more-button"));
                if (showMoreBtn.isDisplayed())
                    showMoreBtn.click();
            } catch (Exception ignored) {
            }

            String title = getTextSafe(wait, By.cssSelector("h1.top-card-layout__title"));
            String description = getTextSafe(wait, By.cssSelector("p[data-test-id='about-us__description']"));

            String website = "";
            try {
                WebElement websiteEl = driver
                        .findElement(By.cssSelector("a[data-tracking-control-name='about_website']"));
                website = extractRealUrl(websiteEl.getAttribute("href"));
            } catch (Exception ignored) {
            }

            String industry = getTextSafe(driver, By.cssSelector("div[data-test-id='about-us__industry'] dd"));
            String companySize = getTextSafe(driver, By.cssSelector("div[data-test-id='about-us__size'] dd"));
            String headquarters = getTextSafe(driver, By.cssSelector("div[data-test-id='about-us__headquarters'] dd"));
            String companyType = getTextSafe(driver,
                    By.cssSelector("div[data-test-id='about-us__organizationType'] dd"));

            JSONArray locationsArray = new JSONArray();
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("div[id^='address-']")));
                List<WebElement> locationBlocks = driver.findElements(By.cssSelector("div[id^='address-']"));
                for (WebElement block : locationBlocks) {
                    String address = block.getText().replace("\n", ", ").trim();
                    if (!address.isEmpty()) {
                        JSONObject locJson = new JSONObject();
                        locJson.put("address", address);
                        locationsArray.put(locJson);
                    }
                }
            } catch (Exception ignored) {
            }

            JSONObject companyJson = new JSONObject();
            companyJson.put("company_url", companyUrl);
            companyJson.put("title", title);
            companyJson.put("description", description);
            companyJson.put("website", website);
            companyJson.put("industry", industry);
            companyJson.put("company_size", companySize);
            companyJson.put("company_type", companyType);
            companyJson.put("headquarters", headquarters);
            companyJson.put("locations", locationsArray);

            return companyJson;

        } finally {
            driver.quit();
        }
    }

    private static JSONArray getTaskBatchFromServer(String clientId) throws Exception {
        HttpPost post = new HttpPost(SERVER_URL + "/get-tasks");
        JSONObject body = new JSONObject().put("client_id", clientId);
        post.setEntity(new StringEntity(body.toString(), ContentType.APPLICATION_JSON));

        try (CloseableHttpClient client = HttpClients.createDefault();
                CloseableHttpResponse response = client.execute(post)) {

            String json = EntityUtils.toString(response.getEntity());
            JSONObject obj = new JSONObject(json);
            if (obj.has("status"))
                return new JSONArray();
            return obj.getJSONArray("tasks");
        }
    }

    private static void submitDataToServer(int rowNumber, JSONObject companyJson) throws Exception {
        HttpPost post = new HttpPost(SERVER_URL + "/submit-data");
        JSONObject payload = new JSONObject();
        payload.put("row_number", rowNumber);
        payload.put("company_data", companyJson);
        post.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            client.execute(post);
        }
    }

    private static String getTextSafe(WebDriver driver, By locator) {
        try {
            return driver.findElement(locator).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getTextSafe(WebDriverWait wait, By locator) {
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(locator)).getText().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractRealUrl(String linkedInUrl) {
        try {
            if (linkedInUrl.contains("url=")) {
                String part = linkedInUrl.split("url=")[1];
                return java.net.URLDecoder.decode(part.split("&")[0], "UTF-8");
            }
            return linkedInUrl;
        } catch (Exception e) {
            return linkedInUrl;
        }
    }

    private static void closePopupIfPresent(WebDriver driver) {
        try {
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(3));
            WebElement closeBtn = wait
                    .until(ExpectedConditions.elementToBeClickable(By.cssSelector("button.modal__dismiss")));
            closeBtn.click();
        } catch (Exception ignored) {
        }
    }
}
