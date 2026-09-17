import { icons } from "./vendor/lucide-icons.js";

const $ = (selector) => document.querySelector(selector);
const base = "/api/v2/deployment-applications";
const tokenKey = "deploydock.accessToken";
const terminal = new Set(["SUCCEEDED", "FAILED", "ABORTED", "ROLLED_BACK"]);
const statuses = { QUEUED: "대기", RUNNING: "배포 중", AWAITING_APPROVAL: "승인 대기", PROMOTING: "전환 중", ABORTING: "복구 중", SUCCEEDED: "성공", FAILED: "실패", ABORTED: "중단 완료", ROLLED_BACK: "롤백 완료" };
const strategies = { ROLLING: "롤링", BLUE_GREEN: "블루그린", CANARY: "카나리", GROUPED: "그룹 배치", INDIVIDUAL: "개별 배치" };
const phases = { PREPARE: "원본 상태 저장", APPLY: "신규 버전 적용", WAIT_READY: "Pod 준비 확인", APPROVAL: "검증 및 승인", ROUTE: "가중치 반영 확인", SWITCH: "운영 Service 전환", SWITCH_WAIT: "EndpointSlice 반영 확인", UPDATE_SOURCE: "원본 Deployment 갱신", SOURCE_READY: "원본 Pod 준비 확인", SOURCE_TRAFFIC: "원본 Service 복귀 확인", ROLLBACK_PREVIEW: "롤백 준비", RESTORE: "원본 복원", RESTORE_WAIT: "복원 결과 확인" };
const state = { apps: [], namespaces: [], configs: [], runs: [], appId: null, runId: null, capabilities: null, busy: false, fresh: false, authenticated: false };
let detailVersion = 0;
let initializationVersion = 0;
let pollTimer;
let toastTimer;
let confirmation;

function node(tag, text, className) {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = text;
    if (className) element.className = className;
    return element;
}

function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    for (const [key, value] of Object.entries({ viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", "stroke-width": "2", "stroke-linecap": "round", "stroke-linejoin": "round", "aria-hidden": "true" })) svg.setAttribute(key, value);
    for (const [tag, attributes] of icons[name] || []) {
        const child = document.createElementNS(svg.namespaceURI, tag);
        for (const [key, value] of Object.entries(attributes)) child.setAttribute(key, value);
        svg.append(child);
    }
    return svg;
}

document.querySelectorAll("[data-icon]").forEach((element) => element.replaceWith(icon(element.dataset.icon)));
const option = (value, label) => Object.assign(node("option", label), { value });
const app = () => state.apps.find((value) => value.id === state.appId);
const selectedRun = () => state.runs.find((value) => value.id === state.runId);
const configuration = (run) => run.configuration || state.configs.find((value) => value.id === run.configurationId);
const weighted = (config) => config?.webStrategy === "CANARY" && Boolean(config.trafficAdapter || config.canaryRoute);
const activeRun = () => state.runs.some((run) => !terminal.has(run.status));
const path = (suffix = "") => `${base}/${encodeURIComponent(state.appId)}${suffix}`;
const date = (value) => new Date(value).toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });

function message(selector, text) {
    $(selector).textContent = text || "";
    $(selector).hidden = !text;
}

function notify(text) {
    clearTimeout(toastTimer);
    message("#toast", text);
    toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 4500);
}

function signedOut() {
    state.authenticated = false;
    state.fresh = false;
    detailVersion++;
    initializationVersion++;
    clearTimeout(pollTimer);
    localStorage.removeItem(tokenKey);
    document.querySelectorAll("dialog[open]").forEach((dialog) => dialog.close());
    $("#workspace").hidden = true;
    $("#login-panel").hidden = false;
    $("#logout").hidden = true;
    $("#session-user").textContent = "";
    $("#connection").textContent = "로그인 필요";
    syncControls();
}

async function api(url, options = {}) {
    const token = localStorage.getItem(tokenKey);
    const response = await fetch(url, { ...options, signal: options.signal || AbortSignal.timeout(15000), headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) } });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
        if (response.status === 401) signedOut();
        const error = new Error(response.status === 401 ? "세션이 만료되었거나 로그인 정보가 올바르지 않습니다." : body?.message || `요청 실패 (${response.status})`);
        error.status = response.status;
        throw error;
    }
    return body;
}

async function mutate(action, errorSelector = "#page-error") {
    if (state.busy) return;
    state.busy = true;
    message(errorSelector, "");
    syncControls();
    try { await action(); }
    catch (error) { message(errorSelector, error.message || "서버 연결에 실패했습니다."); }
    finally { state.busy = false; syncControls(); }
}

function syncControls() {
    $("#register-open").disabled = state.busy || !state.authenticated || !state.namespaces.length;
    $("#refresh").disabled = state.busy;
    document.querySelectorAll('button[type="submit"], #confirm-submit, [data-close]').forEach((button) => { button.disabled = state.busy; });
    document.querySelectorAll(".app-item").forEach((button) => { button.disabled = state.busy; });
    renderActions();
    document.querySelectorAll("[data-deploy]").forEach((button) => { button.disabled = state.busy || !state.fresh || activeRun(); });
}

async function initialize() {
    clearTimeout(pollTimer);
    if (!localStorage.getItem(tokenKey)) { signedOut(); return; }
    const version = ++initializationVersion;
    $("#connection").textContent = "연결 중";
    try {
        const [apps, namespaces, capabilities] = await Promise.all([api(base), api("/api/namespaces"), api(`${base}/capabilities`)]);
        if (version !== initializationVersion) return;
        state.apps = apps;
        state.namespaces = namespaces;
        state.capabilities = capabilities;
        state.authenticated = true;
        $("#login-panel").hidden = true;
        $("#workspace").hidden = false;
        $("#logout").hidden = false;
        $("#environment-notice").hidden = !capabilities.preview;
        $("#session-user").textContent = capabilities.preview ? "ui-preview" : "로그인됨";
        const previousFilter = $("#namespace-filter").value;
        $("#namespace-filter").replaceChildren(option("", "전체"), ...namespaces.map((item) => option(item.name, item.name)));
        if (namespaces.some((item) => item.name === previousFilter)) $("#namespace-filter").value = previousFilter;
        $("#register-namespace").replaceChildren(...namespaces.map((item) => option(item.name, item.name)));
        $("#orchestrator").replaceChildren(...capabilities.orchestrators.map((value) => option(value, value)));
        const currentAdapter = $("#traffic-adapter").value;
        $("#traffic-adapter").replaceChildren(option("", "Preview 검증"), ...capabilities.deployment.configuredTrafficAdapters.map((value) => option(value, value)));
        if (capabilities.deployment.configuredTrafficAdapters.includes(currentAdapter)) $("#traffic-adapter").value = currentAdapter;
        const selected = state.appId || decodeURIComponent(location.hash.slice(1));
        await selectApp(apps.some((item) => item.id === selected) ? selected : apps[0]?.id || null, false);
        if (!state.appId || state.fresh) {
            message("#page-error", "");
            $("#connection").textContent = "연결됨";
        }
    } catch (error) {
        if (version !== initializationVersion) return;
        state.fresh = false;
        $("#connection").textContent = "연결 실패";
        message("#page-error", error.message);
    }
    syncControls();
    schedulePoll();
}

function renderApps() {
    const namespace = $("#namespace-filter").value;
    const search = $("#app-search").value.trim().toLowerCase();
    const apps = state.apps.filter((item) => (!namespace || item.namespace === namespace) && item.name.toLowerCase().includes(search));
    $("#app-count").textContent = String(apps.length);
    $("#applications").replaceChildren(...apps.map((item) => {
        const button = node("button", undefined, "app-item");
        button.type = "button";
        button.setAttribute("aria-current", String(item.id === state.appId));
        button.disabled = state.busy;
        const text = node("div");
        text.append(node("strong", item.name), node("small", `${item.namespace} · ${item.kind}`));
        button.append(icon(item.kind === "WEB" ? "Box" : "GitBranch"), text);
        button.addEventListener("click", () => selectApp(item.id));
        return button;
    }));
    if (!apps.length) $("#applications").append(node("p", "일치하는 앱이 없습니다.", "muted"));
}

async function selectApp(id, reset = true) {
    const changed = state.appId !== id;
    state.appId = id;
    if (changed) {
        state.configs = []; state.runs = []; state.runId = null; state.fresh = false;
        $("#configuration-form").reset();
    }
    renderApps();
    $("#selection-empty").hidden = Boolean(id);
    $("#application-detail").hidden = !id;
    if (!id) return;
    history.replaceState(null, "", `#${encodeURIComponent(id)}`);
    $("#app-name").textContent = app().name;
    $("#app-meta").textContent = `${app().namespace} / ${app().name} · ${app().orchestrator}`;
    $("#app-kind").textContent = app().kind === "WEB" ? "Deployment" : "CronJob";
    updateFields();
    renderRuns(); renderConfigurations();
    if (reset) showTab("runs");
    await loadDetail();
}

async function loadDetail() {
    if (!state.appId || !state.authenticated) return;
    const id = state.appId;
    const version = ++detailVersion;
    try {
        const [configs, runs] = await Promise.all([api(path("/configurations")), api(path("/runs"))]);
        if (version !== detailVersion || id !== state.appId || !state.authenticated) return;
        state.configs = configs; state.runs = runs; state.fresh = true;
        if (!runs.some((run) => run.id === state.runId)) state.runId = runs.at(-1)?.id || null;
        renderRuns(); renderConfigurations();
        $("#connection").textContent = `갱신 ${new Date().toLocaleTimeString("ko-KR", { hour12: false })}`;
        message("#page-error", "");
    } catch (error) {
        if (version !== detailVersion || id !== state.appId) return;
        state.fresh = false;
        $("#connection").textContent = "갱신 실패";
        message("#page-error", error.message);
    }
    syncControls();
}

function schedulePoll() {
    clearTimeout(pollTimer);
    if (!state.authenticated) return;
    pollTimer = setTimeout(async () => {
        if (!document.hidden && !state.busy) await loadDetail();
        schedulePoll();
    }, 4000);
}

function statusBadge(run) {
    const badge = node("span", statuses[run.status] || run.status, "badge");
    badge.dataset.status = run.status;
    return badge;
}

function renderRuns() {
    $("#run-count").textContent = `${state.runs.length}건`;
    $("#runs-empty").hidden = Boolean(state.runs.length);
    $("#runs").replaceChildren(...[...state.runs].reverse().map((run) => {
        const row = node("tr"); row.dataset.selected = String(run.id === state.runId);
        const config = configuration(run);
        const select = node("button", `r${config?.revision ?? "?"} · ${run.id.slice(0, 12)}`, "run-select");
        select.type = "button";
        select.addEventListener("click", () => { state.runId = run.id; renderRuns(); });
        const first = node("td"); first.append(select);
        const status = node("td"); status.append(statusBadge(run));
        row.append(first, node("td", strategies[run.webStrategy || run.batchMode] || run.kind), status, node("td", date(run.requestedAt)), node("td", run.requestedBy));
        return row;
    }));
    const run = selectedRun();
    $("#run-detail").hidden = !run;
    if (!run) return;
    const config = configuration(run);
    $("#run-title").textContent = `${strategies[run.webStrategy || run.batchMode]} · r${config?.revision ?? "?"}`;
    $("#run-id").textContent = run.id;
    $("#run-status").replaceWith(Object.assign(statusBadge(run), { id: "run-status" }));
    $("#run-phase").textContent = terminal.has(run.status) ? statuses[run.status] : `${phases[run.phase] || run.phase}${run.action ? ` · ${run.action} 접수됨` : ""}`;
    $("#run-image").textContent = config?.image || "설정 없음";
    $("#run-engine").textContent = run.orchestrator;
    $("#run-traffic").textContent = run.kind === "BATCH" ? "해당 없음" : weighted(config) ? `가중치 제어 · 신규 ${run.result?.canaryWeight ?? 0}%` : run.webStrategy === "CANARY" ? "PREVIEW_ONLY · 비율 분배 없음" : "운영 Service";
    message("#run-error", run.error ? `배포 오류: ${run.error}` : "");
    message("#recovery-error", run.recoveryError ? `복구 대기: ${run.recoveryError}` : "");
    renderProgress(run);
    $("#preview").hidden = !run.result?.previewService;
    if (run.result?.previewService) {
        $("#preview-service").textContent = run.result.previewService;
        const port = $("#preview-port").value;
        $("#preview-port").replaceChildren(...run.result.previewPorts.map((value) => option(String(value), String(value))));
        if (run.result.previewPorts.map(String).includes(port)) $("#preview-port").value = port;
        renderPreview();
    }
    renderActions();
}

function renderProgress(run) {
    let labels = ["접수", "신규 Pod", "검증·승인", "전환", "완료"];
    let position = { PREPARE: 0, APPLY: 1, WAIT_READY: 1, APPROVAL: 2, ROUTE: 2, SWITCH: 3, SWITCH_WAIT: 3, UPDATE_SOURCE: 3, SOURCE_READY: 3, SOURCE_TRAFFIC: 3 }[run.phase] ?? 1;
    if (run.kind === "BATCH") { labels = ["접수", "템플릿 적용", "완료"]; position = run.phase === "PREPARE" ? 0 : 1; }
    else if (run.webStrategy === "ROLLING") { labels = ["접수", "이미지 적용", "준비 확인", "완료"]; position = { PREPARE: 0, APPLY: 1, WAIT_READY: 2 }[run.phase] ?? 2; }
    if (terminal.has(run.status)) { position = labels.length - 1; labels[position] = statuses[run.status]; }
    if (run.status === "ABORTING") { labels = ["배포", "복구", "복구 완료"]; position = 1; }
    $("#progress").style.gridTemplateColumns = `repeat(${labels.length}, minmax(0, 1fr))`;
    $("#progress").replaceChildren(...labels.map((label, index) => {
        const item = node("li", label, run.status === "SUCCEEDED" ? "complete" : index === position ? "current" : index < position && !["FAILED", "ABORTED", "ROLLED_BACK"].includes(run.status) ? "complete" : "");
        if (index === position) item.setAttribute("aria-current", "step");
        return item;
    }));
}

function renderPreview() {
    const run = selectedRun();
    const local = Number($("#local-port").value);
    const remote = $("#preview-port").value;
    if (!run?.result?.previewService) return;
    const valid = Number.isInteger(local) && local >= 1024 && local <= 65535 && /^\d+$/.test(remote);
    const retired = terminal.has(run.status);
    $("#preview-state").textContent = retired ? "실행 종료 · preview Pod 중지됨" : run.status === "AWAITING_APPROVAL" ? "신규 Pod 준비 완료" : "신규 버전 준비·전환 중";
    const quote = (value) => `'${String(value).replaceAll("'", "'\\''")}'`;
    $("#preview-command").textContent = valid ? `kubectl -n ${quote(app().namespace)} port-forward ${quote(`service/${run.result.previewService}`)} ${local}:${remote} --address=127.0.0.1` : "포트 값을 확인해 주세요.";
    $("#copy-preview").disabled = !valid || retired;
    $("#preview-link").hidden = !valid || retired;
    $("#preview-link").href = `http://127.0.0.1:${valid ? local : 18080}`;
}

function renderActions() {
    const run = selectedRun();
    $("#run-actions").replaceChildren();
    if (!run) return;
    const config = configuration(run);
    const actions = [];
    if (!terminal.has(run.status) && run.status !== "ABORTING") actions.push(["ABORT", "중단", "button-danger"]);
    if (run.status === "AWAITING_APPROVAL") {
        if (weighted(config) && run.step + 1 < config.canarySteps.length) actions.push(["ADVANCE", `${config.canarySteps[run.step + 1]}% 단계 승인`, "button-primary"]);
        else actions.push(["PROMOTE", "승격 승인", "button-primary"]);
    }
    if (run.status === "SUCCEEDED" && state.runs.at(-1)?.id === run.id && !activeRun()) actions.push(["ROLLBACK", "이전 버전으로 롤백", "button-danger"]);
    actions.forEach(([action, label, style]) => {
        const button = node("button", label, `button ${style}`);
        button.type = "button"; button.disabled = state.busy || !state.fresh || Boolean(run.action);
        button.addEventListener("click", () => confirmAction(action, label, run));
        $("#run-actions").append(button);
    });
}

function showTab(tab) {
    for (const name of ["runs", "config"]) {
        $(`#${name}-tab`).setAttribute("aria-selected", String(name === tab));
        $(`#${name}-tab`).tabIndex = name === tab ? 0 : -1;
        $(`#${name}-view`).hidden = name !== tab;
    }
}

function updateFields() {
    const web = app()?.kind === "WEB";
    $("#web-fields").hidden = !web;
    $("#batch-fields").hidden = web;
    $("#canary-fields").hidden = !web || $("#strategy").value !== "CANARY";
    const adapter = $("#traffic-adapter").value;
    $("#canary-steps-label").hidden = !adapter;
    $("#traffic-options-label").hidden = !adapter;
}

function renderConfigurations() {
    const latest = state.configs.reduce((selected, config) => !selected || config.revision > selected.revision ? config : selected, null);
    $("#configuration-count").textContent = latest ? `최신 r${latest.revision}` : "저장 전";
    $("#configurations").replaceChildren(...(latest ? [latest] : []).map((config) => {
        const row = node("div", undefined, "configuration-row");
        const summary = node("div");
        summary.append(node("strong", `r${config.revision} · ${strategies[config.webStrategy || config.batchMode]}`), node("p", config.image, "mono"), node("p", `${config.batchMode ? config.batchTargets.join(", ") || app().name : config.replicas ? `${config.replicas} Pods` : "Pod 수 유지"} · ${date(config.savedAt)}`));
        const button = node("button", undefined, "button button-primary");
        button.type = "button"; button.dataset.deploy = config.id;
        button.append(icon("Play"), document.createTextNode("배포 실행"));
        button.disabled = state.busy || !state.fresh || activeRun();
        button.addEventListener("click", () => {
            const targetApp = app();
            openConfirmation("배포 실행", `${targetApp.namespace} / ${targetApp.name}\nr${config.revision} · ${config.image}`, async () => {
                const run = await postIdempotent(`${base}/${encodeURIComponent(targetApp.id)}/runs`, { configurationId: config.id }, `${targetApp.id}:submit:${config.id}`);
                state.runId = run.id;
                showTab("runs");
            });
        });
        row.append(summary, button); return row;
    }));
    if (!state.configs.length) $("#configurations").append(node("p", "저장된 설정이 없습니다.", "muted"));
}

function openConfirmation(title, summary, action) {
    confirmation = action;
    $("#confirm-title").textContent = title;
    $("#confirm-summary").textContent = summary;
    message("#confirm-error", "");
    $("#confirm-submit").textContent = title;
    $("#confirm-dialog").showModal();
}

function confirmAction(action, label, run) {
    const targetApp = app();
    openConfirmation(label, `${targetApp.namespace} / ${targetApp.name}\n${run.id}`, () => postIdempotent(
        `${base}/${encodeURIComponent(targetApp.id)}/runs/${encodeURIComponent(run.id)}/actions`, { action }, `${run.id}:${action}:${run.step}`,
    ));
}

async function postIdempotent(url, body, key) {
    const storageKey = `deploydock.request:${key}`;
    const requestId = sessionStorage.getItem(storageKey) || crypto.randomUUID();
    sessionStorage.setItem(storageKey, requestId);
    const result = await api(url, { method: "POST", body: JSON.stringify({ ...body, requestId }) });
    sessionStorage.removeItem(storageKey);
    return result;
}

$("#confirm-submit").addEventListener("click", () => mutate(async () => {
    await confirmation();
    $("#confirm-dialog").close();
    await loadDetail();
    notify("요청을 접수했습니다.");
}, "#confirm-error"));

$("#configuration-form").addEventListener("submit", (event) => {
    event.preventDefault();
    mutate(async () => {
        const data = new FormData(event.target);
        const body = { image: data.get("image").trim(), progressDeadlineSeconds: Number(data.get("progressDeadlineSeconds")) };
        if (app().kind === "WEB") {
            body.webStrategy = data.get("webStrategy");
            if (data.get("replicas")) body.replicas = Number(data.get("replicas"));
            if (body.webStrategy === "CANARY" && data.get("trafficAdapter")) {
                body.trafficAdapter = data.get("trafficAdapter");
                body.canarySteps = data.get("canarySteps").split(",").map((value) => Number(value.trim()));
                if (body.canarySteps.some((value, index, values) => !Number.isInteger(value) || value < 1 || value > 99 || (index > 0 && value <= values[index - 1]))) throw new Error("가중치는 1~99 사이의 증가하는 정수로 입력해 주세요.");
                try { body.trafficOptions = JSON.parse(data.get("trafficOptions")); }
                catch { throw new Error("어댑터 설정의 JSON 형식을 확인해 주세요."); }
                if (!body.trafficOptions || Array.isArray(body.trafficOptions) || typeof body.trafficOptions !== "object" || Object.values(body.trafficOptions).some((value) => typeof value !== "string")) throw new Error("어댑터 설정은 문자열 값을 가진 JSON 객체여야 합니다.");
            }
        } else {
            body.batchMode = data.get("batchMode");
            body.batchTargets = data.get("batchTargets").split(",").map((value) => value.trim()).filter(Boolean);
        }
        await api(path("/configurations"), { method: "POST", body: JSON.stringify(body) });
        await loadDetail();
        notify("설정을 저장했습니다.");
    });
});

$("#register-form").addEventListener("submit", (event) => {
    event.preventDefault();
    mutate(async () => {
        const data = new FormData(event.target);
        const body = Object.fromEntries(["name", "namespace", "kind", "orchestrator"].map((key) => [key, data.get(key)]));
        if (data.get("containerName").trim()) body.containerName = data.get("containerName").trim();
        if (body.kind === "WEB" && data.get("serviceName").trim()) body.serviceName = data.get("serviceName").trim();
        const created = await api(base, { method: "POST", body: JSON.stringify(body) });
        state.appId = created.id;
        state.configs = []; state.runs = []; state.runId = null; state.fresh = false;
        $("#configuration-form").reset();
        $("#register-dialog").close();
        await initialize(); showTab("config");
        notify("앱을 등록했습니다.");
    }, "#register-error");
});

$("#login-form").addEventListener("submit", (event) => {
    event.preventDefault();
    mutate(async () => {
        const data = new FormData(event.target);
        const response = await api("/api/auth/login", { method: "POST", body: JSON.stringify(Object.fromEntries(data)) });
        localStorage.setItem(tokenKey, response.accessToken);
        event.target.reset();
        await initialize();
    });
});

$("#register-open").addEventListener("click", () => { message("#register-error", ""); $("#register-dialog").showModal(); });
$("#register-kind").addEventListener("change", () => { $("#service-label").hidden = $("#register-kind").value !== "WEB"; });
$("#refresh").addEventListener("click", initialize);
$("#logout").addEventListener("click", signedOut);
$("#namespace-filter").addEventListener("change", renderApps);
$("#app-search").addEventListener("input", renderApps);
$("#runs-tab").addEventListener("click", () => showTab("runs"));
$("#config-tab").addEventListener("click", () => showTab("config"));
$(".view-tabs").addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const tab = event.key === "Home" ? "runs" : event.key === "End" ? "config" : event.target.id === "runs-tab" ? "config" : "runs";
    showTab(tab);
    $(`#${tab}-tab`).focus();
});
$("#strategy").addEventListener("change", updateFields);
$("#traffic-adapter").addEventListener("change", () => {
    if ($("#traffic-adapter").value === "gateway-api" && $("[name=trafficOptions]").value === "{}") $("[name=trafficOptions]").value = JSON.stringify({ routeName: app()?.name || "" }, null, 2);
    updateFields();
});
$("#local-port").addEventListener("input", renderPreview);
$("#preview-port").addEventListener("change", renderPreview);
$("#copy-preview").addEventListener("click", async () => {
    try { await navigator.clipboard.writeText($("#preview-command").textContent); notify("명령을 복사했습니다."); }
    catch { message("#page-error", "클립보드에 접근할 수 없습니다."); }
});
document.querySelectorAll("[data-close]").forEach((button) => button.addEventListener("click", () => button.closest("dialog").close()));
document.querySelectorAll("dialog").forEach((dialog) => dialog.addEventListener("cancel", (event) => { if (state.busy) event.preventDefault(); }));
document.addEventListener("visibilitychange", () => { if (!document.hidden && state.authenticated && !state.busy) loadDetail(); });
window.addEventListener("storage", (event) => { if (event.key === tokenKey && !event.newValue) signedOut(); });
initialize();
