package com.leavemanagement.ui;

import com.leavemanagement.RecordingMailSender;
import com.leavemanagement.entity.*;
import com.leavemanagement.repository.*;
import org.junit.jupiter.api.*;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Browser tests: a real Microsoft Edge or Google Chrome (headless) clicks through the app on a random port with its own database.
 * The scenarios run in order and share data: an employee applies, the Team Lead approves, HR gives final approval,
 * then the dashboards, exports, employee creation, comp-off and logout are checked.
 * Skipped (not failed) when Edge cannot be started. Screenshots: target/ui-screenshots.
 */
@Tag("ui")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/ui-${random.uuid}.db?date_class=TEXT",
        "app.admin.password=AdminPass123!"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UiTests {
    static final String PASSWORD = "Secret123!";
    static final Path SCREENSHOTS = Path.of("target", "ui-screenshots");

    @LocalServerPort int port;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired UserAccountRepository userAccountRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingMailSender mailSender;

    WebDriver driver;
    WebDriverWait wait;
    Employee hr, lead, member, solo;
    LocalDate start = LocalDate.now().plusDays(14);
    int year = start.getYear();

    @BeforeAll
    void setUp() throws IOException {
        hr = employee("Hana", "HR", "hr@ui.test", null);
        account(hr, Role.HR_ADMIN, false);
        lead = employee("Lee", "IT", "lead@ui.test", null);
        account(lead, Role.TEAM_LEAD, false);
        member = employee("Mia", "IT", "mia@ui.test", lead.getEmployeeId());
        account(member, Role.EMPLOYEE, false);
        solo = employee("Sam", "Sales", "sam@ui.test", null);
        account(solo, Role.EMPLOYEE, false);
        Employee newcomer = employee("Nina", "IT", "nina@ui.test", lead.getEmployeeId());
        account(newcomer, Role.EMPLOYEE, true); // still has a temporary password

        LeaveType casual = leaveTypeRepository.save(new LeaveType(null, "Casual Leave", 12, null, null));
        leaveBalanceRepository.save(new LeaveBalance(null, member.getEmployeeId(), monthly(),
                new BigDecimal("12"), BigDecimal.ZERO, BigDecimal.ZERO, LeaveBalance.NO_YEAR));
        leaveBalanceRepository.save(new LeaveBalance(null, solo.getEmployeeId(), monthly(),
                new BigDecimal("2"), new BigDecimal("3"), BigDecimal.ZERO, LeaveBalance.NO_YEAR)); // already below zero

        driver = startBrowser();
        System.out.println("UI TESTS running in " + ((HasCapabilities) driver).getCapabilities().getBrowserName()
                + " " + ((HasCapabilities) driver).getCapabilities().getBrowserVersion());
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        Files.createDirectories(SCREENSHOTS);
    }

    @AfterEach
    void screenshot(TestInfo info) throws IOException {
        if (driver instanceof TakesScreenshot camera) {
            String name = info.getTestMethod().map(m -> m.getName()).orElse("step");
            Files.write(SCREENSHOTS.resolve(name + ".png"), camera.getScreenshotAs(OutputType.BYTES));
        }
    }

    @AfterAll
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    // --- Scenarios ---

    @Test
    @Order(1)
    void wrongPasswordShowsAnError() {
        open("/login");
        login("mia@ui.test", "wrong-password");
        assertThat(driver.getCurrentUrl()).endsWith("/login?error");
        assertThat(text(".alert-danger")).contains("Incorrect email or password");
    }

    @Test
    @Order(2)
    void temporaryPasswordMustBeChangedAtFirstLogin() {
        login("nina@ui.test", PASSWORD);
        wait.until(ExpectedConditions.urlContains("/change-password"));
        type("currentPassword", PASSWORD);
        type("newPassword", "BrandNew456!");
        type("confirmPassword", "BrandNew456!");
        clickWhenReady(By.cssSelector("#changePasswordForm button[type=submit]"));
        wait.until(ExpectedConditions.urlMatches(".*/lms/$"));
        assertThat(text("nav")).contains("nina@ui.test").contains("Employee");
        logout();
    }

    @Test
    @Order(3)
    void employeeAppliesForLeave() {
        login("mia@ui.test", PASSWORD);
        open("/leave-requests");
        wait.until(d -> new Select(d.findElement(By.id("leaveTypeId"))).getOptions().size() > 1);
        new Select(driver.findElement(By.id("leaveTypeId"))).selectByVisibleText("Casual Leave");
        setDate("startDate", start);
        setDate("endDate", start.plusDays(1));
        type("reason", "Family function");
        clickWhenReady(By.cssSelector("#leaveRequestForm button[type=submit]"));
        waitForText("#leaveRequestsList", "Waiting for Team Lead");
        assertThat(text("#leaveRequestsList")).contains("Family function");
    }

    @Test
    @Order(4)
    void employeeHasNoDashboard() {
        assertThat(driver.findElements(By.linkText("Dashboard"))).isEmpty();
        open("/dashboard");
        assertThat(driver.getPageSource()).contains("403");
        open("/"); // the error page has no navigation bar
        logout();
    }

    @Test
    @Order(5)
    void teamLeadApprovesFromTheTeamTab() {
        login("lead@ui.test", PASSWORD);
        open("/leave-requests");
        clickWhenReady(By.cssSelector("#tab-team .nav-link"));
        waitForText("#leaveRequestsList", "Mia Test");
        clickWhenReady(By.cssSelector("#leaveRequestsList button.btn-success"));
        answerPrompt("Enjoy");
        waitForText("#noticeArea", "Approved and sent to HR for final approval");
        // The message floats in the corner, so it is seen wherever the page is scrolled
        WebElement notice = driver.findElement(By.cssSelector("#noticeArea .alert-success"));
        assertThat(driver.findElement(By.id("noticeArea")).getCssValue("position")).isEqualTo("fixed");
        assertThat(notice.isDisplayed()).isTrue();
        notice.findElement(By.cssSelector(".btn-close")).click();
        wait.until(ExpectedConditions.stalenessOf(notice));
        logout();
    }

    @Test
    @Order(6)
    void hrGivesFinalApprovalAndTheBalanceDrops() {
        login("hr@ui.test", PASSWORD);
        open("/leave-requests");
        clickWhenReady(By.cssSelector("#tab-hr .nav-link"));
        waitForText("#leaveRequestsList", "Waiting for HR");
        clickWhenReady(By.cssSelector("#leaveRequestsList button.btn-success"));
        answerPrompt("Approved");
        waitForText("body", "Leave approved");

        open("/leave-balance?employeeId=" + member.getEmployeeId() + "&year=" + year);
        waitForText("#leaveBalanceList", "Monthly Leave");
        WebElement row = driver.findElement(By.cssSelector("#leaveBalanceList tr"));
        assertThat(row.findElements(By.tagName("td")).get(3).getText()).isEqualTo("2");    // used
        assertThat(row.findElements(By.tagName("td")).get(4).getText()).startsWith("10"); // remaining
        waitForText("#ledgerList", "Leave taken");
    }

    @Test
    @Order(7)
    void hrDashboardShowsCardsTablesAndFilters() {
        open("/dashboard");
        assertThat(text("h1")).isEqualTo("HR Dashboard");
        wait.until(d -> !d.findElement(By.id("activeEmployees")).getText().equals("-"));
        assertThat(text("#activeEmployees")).isEqualTo("5");
        assertThat(text("#negativeCount")).isEqualTo("1");
        assertThat(driver.findElement(By.id("negativeCard")).getAttribute("class")).contains("bg-danger");
        waitForText("#negativeList", "Sam Test");
        waitForText("#balanceList", "Mia Test");

        new Select(driver.findElement(By.id("filterDepartment"))).selectByVisibleText("Sales");
        wait.until(d -> !d.findElement(By.id("balanceList")).getText().contains("Mia Test"));
        assertThat(text("#balanceList")).contains("Sam Test");
        assertThat(driver.findElement(By.id("exportBalances")).getAttribute("href")).contains("department=Sales");
    }

    @Test
    @Order(8)
    void excelExportDownloadsWithTheLoginCookie() throws Exception {
        String href = driver.findElement(By.id("exportBalances")).getAttribute("href");
        Cookie login = driver.manage().getCookieNamed("LMS_TOKEN");
        assertThat(login).isNotNull();
        assertThat(login.isHttpOnly()).isTrue();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(href)).header("Cookie", "LMS_TOKEN=" + login.getValue()).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("leave-balance-" + year + "-sales");
        assertThat(new String(response.body(), 0, 2)).isEqualTo("PK");
    }

    @Test
    @Order(9)
    void hrCreatesAnEmployeeAndThePasswordIsEmailed() {
        open("/employees");
        type("firstName", "Omar");
        type("lastName", "Khan");
        type("email", "omar@ui.test");
        type("department", "Sales");
        type("designation", "Analyst");
        type("salary", "42000");
        setDate("dateOfJoining", LocalDate.of(2026, 1, 5));
        new Select(driver.findElement(By.id("role"))).selectByValue("EMPLOYEE");
        clickWhenReady(By.id("submitBtn"));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("sentNotice")));
        assertThat(text("#sentNoticeText"))
                .isEqualTo("Employee added. The temporary password has been sent to omar@ui.test.");
        assertThat(mailSender.last().getTo()).containsExactly("omar@ui.test");
        assertThat(mailSender.lastTemporaryPassword()).hasSize(12);
        waitForText("#employeesList", "Omar Khan");
        logout();
    }

    @Test
    @Order(10)
    void teamLeadDashboardShowsOnlyTheirTeam() {
        login("lead@ui.test", PASSWORD);
        open("/dashboard");
        assertThat(text("h1")).isEqualTo("My Team");
        assertThat(driver.findElements(By.id("hrDashboard"))).isEmpty();
        wait.until(d -> !d.findElement(By.id("teamSize")).getText().equals("-"));
        assertThat(text("#teamSize")).isEqualTo("2"); // Mia and Nina
        waitForText("#teamBalanceList", "Mia Test");
        assertThat(text("#teamBalanceList")).doesNotContain("Sam Test");
        logout();
    }

    @Test
    @Order(11)
    void employeeRequestsCompOff() {
        LocalDate saturday = LocalDate.now().minusDays(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        login("mia@ui.test", PASSWORD);
        open("/comp-off");
        setDate("workedFrom", saturday);
        setDate("workedTo", saturday);
        type("compOffReason", "Production release");
        clickWhenReady(By.cssSelector("#compOffForm button[type=submit]"));
        waitForText("body", "Comp-off request for 1 day(s)");
        waitForText("#compOffList", "Production release");
    }

    @Test
    @Order(12)
    void logoutEndsTheLogin() {
        logout();
        assertThat(driver.getCurrentUrl()).endsWith("/login?logout");
        assertThat(driver.manage().getCookieNamed("LMS_TOKEN")).isNull();
        open("/leave-requests");
        assertThat(driver.getCurrentUrl()).endsWith("/login");
    }

    @Test
    @Order(13)
    void loggingInAsSomeoneElseWithoutLoggingOutSwitchesUser() {
        login("lead@ui.test", PASSWORD);
        assertThat(text("nav")).contains("lead@ui.test");
        login("hr@ui.test", PASSWORD); // still holding the lead's login cookie
        open("/dashboard");
        assertThat(text("nav")).contains("hr@ui.test").contains("(HR)");
        assertThat(text("h1")).isEqualTo("HR Dashboard");
        logout();
    }

    // --- Helpers ---

    // Microsoft Edge, or Google Chrome when Edge's driver cannot be obtained; both headless
    private WebDriver startBrowser() {
        String[] args = {"--headless=new", "--window-size=1400,1000", "--disable-gpu"};
        List<String> problems = new java.util.ArrayList<>();
        try {
            return new EdgeDriver(new EdgeOptions().addArguments(args));
        } catch (Exception e) {
            problems.add("Edge: " + e.getMessage().lines().findFirst().orElse(""));
        }
        try {
            return new ChromeDriver(new ChromeOptions().addArguments(args));
        } catch (Exception e) {
            problems.add("Chrome: " + e.getMessage().lines().findFirst().orElse(""));
        }
        System.out.println("UI TESTS SKIPPED - no browser could be started: " + problems);
        Assumptions.abort("No browser could be started: " + problems);
        return null;
    }

    private void open(String path) {
        driver.get("http://localhost:" + port + "/lms" + path);
    }

    private void login(String email, String password) {
        open("/login");
        type("username", email);
        type("password", password);
        // The sign-in form's button (when already logged in, the navigation bar also has a Logout form)
        driver.findElement(By.cssSelector("form[action$='/login'] button[type=submit]")).click();
        // Wait for the login to finish (redirect to home, change-password or the error page)
        wait.until(d -> !d.getCurrentUrl().endsWith("/lms/login"));
    }

    private void logout() {
        // Messages are added at the top of the page and push the navigation bar down, so go to the top first
        ((JavascriptExecutor) driver).executeScript("window.scrollTo({top: 0, behavior: 'instant'});");
        clickWhenReady(By.cssSelector("nav form button[type=submit]"));
        wait.until(ExpectedConditions.urlContains("/login"));
    }

    private void type(String id, String value) {
        WebElement input = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(id)));
        input.clear();
        input.sendKeys(value);
    }

    // Date inputs are filled through JavaScript: typing depends on the browser's locale
    private void setDate(String id, LocalDate date) {
        WebElement input = driver.findElement(By.id(id));
        ((JavascriptExecutor) driver).executeScript(
                "arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('change', {bubbles: true}));",
                input, date.toString());
    }

    // Scrolls the element to the middle of the window first (instantly: Bootstrap scrolls smoothly by default)
    // Tries again if the page was still re-drawing (e.g. a list arriving) when the click landed
    private void clickWhenReady(By by) {
        for (int attempt = 1; ; attempt++) {
            try {
                WebElement element = wait.until(ExpectedConditions.presenceOfElementLocated(by));
                ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center', behavior: 'instant'});", element);
                wait.until(ExpectedConditions.elementToBeClickable(element)).click();
                return;
            } catch (ElementClickInterceptedException | StaleElementReferenceException e) {
                if (attempt == 3) {
                    throw e;
                }
            }
        }
    }

    private void answerPrompt(String text) {
        Alert prompt = wait.until(ExpectedConditions.alertIsPresent());
        prompt.sendKeys(text);
        prompt.accept();
    }

    private void waitForText(String css, String expected) {
        wait.until(ExpectedConditions.textToBePresentInElementLocated(By.cssSelector(css), expected));
    }

    private String text(String css) {
        return driver.findElement(By.cssSelector(css)).getText().trim();
    }

    private Employee employee(String firstName, String department, String email, Integer leadId) {
        Employee e = new Employee();
        e.setFirstName(firstName);
        e.setLastName("Test");
        e.setEmail(email);
        e.setDepartment(department);
        e.setDesignation("Engineer");
        e.setDateOfJoining(LocalDate.of(2024, 1, 15));
        e.setSalary(50000.0);
        e.setStatus(EmployeeStatus.ACTIVE);
        e.setReporting_LeadId(leadId);
        return employeeRepository.save(e);
    }

    private void account(Employee e, Role role, boolean mustChangePassword) {
        UserAccount a = new UserAccount();
        a.setUsername(e.getEmail());
        a.setPasswordHash(passwordEncoder.encode(PASSWORD));
        a.setRole(role);
        a.setEmployeeId(e.getEmployeeId());
        a.setEnabled(true);
        a.setMustChangePassword(mustChangePassword);
        userAccountRepository.save(a);
    }

    // The shared monthly balance every leave type except comp-off uses
    private Integer monthly() {
        return leaveTypeRepository.findBySystemCode("MONTHLY").orElseThrow().getLeaveTypeId();
    }
}
