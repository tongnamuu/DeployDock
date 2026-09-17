import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");
const origin = process.env.UI_URL || "http://127.0.0.1:4173";
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const errors = [];
page.on("pageerror", (error) => errors.push(error.message));
page.setDefaultTimeout(15000);
const status = (value) => page.locator(`#run-status[data-status="${value}"]`).waitFor();
const confirm = async (name) => { await page.getByRole("button", { name, exact: true }).click(); await page.locator("#confirm-submit").click(); };
const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, "page overflows horizontally");

try {
    await mkdir("build/ui", { recursive: true });
    await page.goto(`${origin}/deployments.html`);
    await page.locator("#application-detail").waitFor();
    assert.equal(await page.locator("#environment-notice").isVisible(), true);
    if (await page.locator("#run-status").getAttribute("data-status") !== "AWAITING_APPROVAL") {
        await page.getByRole("tab", { name: "배포 설정", exact: true }).click();
        await confirm("배포 실행");
    }
    await status("AWAITING_APPROVAL");
    await noOverflow();
    await page.screenshot({ path: "build/ui/deployments-desktop.png", fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await noOverflow();
    await page.screenshot({ path: "build/ui/deployments-mobile.png", fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    assert.match(await page.locator("#preview-command").textContent(), /port-forward.*18080:80/);
    await page.locator("#local-port").fill("18081");
    assert.match(await page.locator("#preview-link").getAttribute("href"), /18081/);
    await confirm("승격 승인");
    await status("SUCCEEDED");
    await confirm("이전 버전으로 롤백");
    await status("ROLLED_BACK");

    const suffix = Date.now().toString(36);
    await page.getByRole("button", { name: "앱 등록", exact: true }).click();
    await page.locator('#register-form [name="name"]').fill(`ui-canary-${suffix}`);
    await page.locator('#register-form button[type="submit"]').click();
    await page.locator("#config-view").waitFor();
    await page.locator('[name="image"]').fill("registry.example.com/shop:v3");
    await page.locator("#strategy").selectOption("CANARY");
    await page.screenshot({ path: "build/ui/configuration-desktop.png", fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await noOverflow();
    await page.screenshot({ path: "build/ui/configuration-mobile.png", fullPage: true });
    await page.setViewportSize({ width: 1440, height: 1000 });
    assert.equal(await page.locator("#traffic-options-label").isVisible(), false);
    await page.getByRole("button", { name: "설정 저장", exact: true }).click();
    await page.locator(".configuration-row").waitFor();
    let first = true;
    const requestIds = [];
    await page.route("**/deployment-applications/*/runs", async (route) => {
        if (route.request().method() !== "POST") return route.continue();
        requestIds.push(route.request().postDataJSON().requestId);
        if (first) { first = false; await route.fetch(); await route.abort("failed"); }
        else await route.continue();
    });
    await confirm("배포 실행");
    await page.locator("#confirm-error:not([hidden])").waitFor();
    await page.locator("#confirm-submit").click();
    await status("AWAITING_APPROVAL");
    assert.equal(requestIds.length, 2);
    assert.equal(requestIds[0], requestIds[1], "retry must retain requestId");
    await page.unroute("**/deployment-applications/*/runs");
    assert.match(await page.locator("#run-traffic").textContent(), /PREVIEW_ONLY/);
    assert.equal(await page.getByRole("button", { name: /% 단계 승인/ }).count(), 0);
    await confirm("중단");
    await status("ABORTED");

    await page.getByRole("tab", { name: "배포 설정", exact: true }).click();
    await page.locator("#traffic-adapter").selectOption("gateway-api");
    await page.locator('[name="trafficOptions"]').fill("not json");
    await page.getByRole("button", { name: "설정 저장", exact: true }).click();
    await page.locator("#page-error:not([hidden])").waitFor();
    assert.equal(await page.locator(".configuration-row").count(), 1);
    await page.locator('[name="trafficOptions"]').fill('{"routeName":"shop-web"}');
    await page.getByRole("button", { name: "설정 저장", exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll(".configuration-row").length === 2);
    await page.locator("[data-deploy]").first().click();
    await page.locator("#confirm-submit").click();
    await status("AWAITING_APPROVAL");
    assert.equal(await page.getByRole("button", { name: "승격 승인", exact: true }).count(), 0);
    await confirm("10% 단계 승인");
    await page.getByRole("button", { name: "50% 단계 승인", exact: true }).waitFor();
    await confirm("50% 단계 승인");
    await page.getByRole("button", { name: "승격 승인", exact: true }).waitFor();
    await confirm("승격 승인");
    await status("SUCCEEDED");

    await page.locator(".app-item").filter({ hasText: "billing" }).click();
    await page.getByRole("tab", { name: "배포 설정", exact: true }).click();
    assert.equal(await page.locator("#web-fields").isVisible(), false);
    assert.equal(await page.locator("#batch-fields").isVisible(), true);
    await confirm("배포 실행");
    await status("SUCCEEDED");
    assert.equal(await page.locator("#preview").isVisible(), false);

    await page.route("**/deployment-applications/*/runs", (route) => route.fulfill({ status: 403, contentType: "application/json", body: '{"message":"접근 권한이 없습니다."}' }));
    await page.getByRole("button", { name: "새로고침", exact: true }).click();
    await page.locator("#page-error:not([hidden])").waitFor();
    assert.equal(await page.getByRole("button", { name: "이전 버전으로 롤백", exact: true }).isDisabled(), true);
    await page.unroute("**/deployment-applications/*/runs");
    await page.route("**/api/**", (route) => route.fulfill({ status: 401, contentType: "application/json", body: '{}' }));
    await page.getByRole("button", { name: "새로고침", exact: true }).click();
    await page.locator("#login-panel").waitFor();
    assert.equal(await page.locator("#workspace").isVisible(), false);
    assert.equal(await page.evaluate(() => localStorage.getItem("deploydock.accessToken")), null);
    await page.unroute("**/api/**");
    await page.locator('#login-form [name="username"]').fill("ui-preview");
    await page.locator('#login-form [name="password"]').fill("preview-only");
    await page.locator('#login-form button[type="submit"]').click();
    await page.locator("#workspace").waitFor();
    await page.route("**/deployment-applications/capabilities", (route) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ preview: true, deployment: { webStrategies: ["ROLLING", "BLUE_GREEN", "CANARY"], batchModes: ["GROUPED", "INDIVIDUAL"], weightedCanary: false, configuredTrafficAdapters: [] }, orchestrators: ["LOCAL"] }) }));
    await page.getByRole("button", { name: "새로고침", exact: true }).click();
    await page.waitForFunction(() => document.querySelectorAll("#traffic-adapter option").length === 1);
    assert.equal(await page.locator("#orchestrator option").count(), 1);
    assert.deepEqual(errors, []);
    console.log("UI checks passed: desktop/mobile, blue-green promote/rollback, register/save/run, retry identity, canary preview/weighted, batch, 403, 401.");
} catch (error) {
    await page.screenshot({ path: "build/ui/failure.png", fullPage: true });
    throw error;
} finally { await browser.close(); }
