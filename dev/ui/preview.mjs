import http from "node:http";
import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { randomUUID } from "node:crypto";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../src/main/resources/static");
const base = "/api/v2/deployment-applications";
const timestamp = () => new Date().toISOString();
const apps = [
    { id: "app-shop", name: "shop-web", namespace: "team-a", kind: "WEB", orchestrator: "LOCAL", serviceName: "shop-web", createdBy: "ui-preview", createdAt: timestamp() },
    { id: "app-billing", name: "billing", namespace: "team-a", kind: "BATCH", orchestrator: "LOCAL", createdBy: "ui-preview", createdAt: timestamp() },
];
const configurations = new Map([
    ["app-shop", [{ id: "cfg-shop", applicationId: "app-shop", revision: 1, image: "registry.example.com/shop:v2", replicas: 3, webStrategy: "BLUE_GREEN", batchTargets: [], canarySteps: [10, 50], progressDeadlineSeconds: 600, savedBy: "ui-preview", savedAt: timestamp() }]],
    ["app-billing", [{ id: "cfg-billing", applicationId: "app-billing", revision: 1, image: "registry.example.com/billing:v4", batchMode: "GROUPED", batchTargets: ["settlement", "invoice"], savedBy: "ui-preview", savedAt: timestamp() }]],
]);
const runs = new Map([
    ["app-shop", [{ id: "run-preview-001", applicationId: "app-shop", configurationId: "cfg-shop", kind: "WEB", orchestrator: "LOCAL", webStrategy: "BLUE_GREEN", status: "AWAITING_APPROVAL", phase: "APPROVAL", step: -1, requestedBy: "ui-preview", requestedAt: timestamp(), result: { previewService: "shop-web-preview", previewPorts: [80], canaryWeight: 0 } }]],
    ["app-billing", []],
]);
const ids = new Map();
const executions = new Map(apps.map((app) => [app.id, []]));
const templates = new Map([["app-billing", ["settlement", "invoice"].map((name) => ({ name, images: { main: "registry.example.com/billing:v3" } }))]]);
if (process.env.UI_PREVIEW_STATE) {
    const saved = JSON.parse(await readFile(process.env.UI_PREVIEW_STATE, "utf8"));
    apps.splice(0, apps.length, ...saved.apps);
    configurations.clear(); runs.clear();
    for (const app of apps) {
        const previous = saved.configurations[app.id] || [];
        runs.set(app.id, (saved.runs[app.id] || []).map((run) => ({ ...run, configuration: run.configuration || previous.find((config) => config.id === run.configurationId) })));
        configurations.set(app.id, previous.slice(-1));
        executions.set(app.id, saved.executions?.[app.id] || []);
        if (saved.templates?.[app.id]) templates.set(app.id, saved.templates[app.id]);
    }
}
const terminal = (run) => ["SUCCEEDED", "FAILED", "ABORTED", "ROLLED_BACK"].includes(run.status);
const send = (response, status, body) => { response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" }); response.end(JSON.stringify(body)); };
const later = (run, values) => { setTimeout(() => Object.assign(run, values), 1800); };

async function handle(request, response) {
    const url = new URL(request.url, "http://127.0.0.1");
    if (!url.pathname.startsWith("/api/")) {
        const name = url.pathname === "/" ? "/deployments.html" : url.pathname;
        const file = path.resolve(root, `.${decodeURIComponent(name)}`);
        if (!file.startsWith(root + path.sep)) return send(response, 403, { message: "Forbidden" });
        let content = await readFile(file);
        if (["/deployments.html", "/batch.html"].includes(name)) content = Buffer.from(content.toString().replace('id="environment-notice" class="notice" hidden', 'id="environment-notice" class="notice"').replace('<script type="module"', '<script>localStorage.setItem("deploydock.accessToken", "ui-preview");</script><script type="module"'));
        response.writeHead(200, { "Content-Type": { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript" }[path.extname(file)] || "text/plain", "Cache-Control": "no-store" });
        response.end(content); return;
    }
    if (request.headers.authorization !== "Bearer ui-preview" && url.pathname !== "/api/auth/login") return send(response, 401, { message: "UI preview login required" });
    if (url.pathname === "/api/auth/login") return send(response, 200, { accessToken: "ui-preview" });
    if (url.pathname === "/api/namespaces") return send(response, 200, [{ name: "team-a", phase: "Active" }]);
    if (url.pathname === `${base}/capabilities`) return send(response, 200, { preview: true, deployment: { webStrategies: ["ROLLING", "BLUE_GREEN", "CANARY"], batchModes: ["GROUPED", "INDIVIDUAL"], weightedCanary: true, configuredTrafficAdapters: ["gateway-api"] }, orchestrators: ["LOCAL"] });
    let body = {};
    if (request.method === "POST") {
        let data = "";
        for await (const chunk of request) { data += chunk; if (data.length > 100000) return send(response, 413, { message: "Too large" }); }
        body = JSON.parse(data || "{}");
    }
    if (url.pathname === base) {
        if (request.method === "GET") return send(response, 200, apps);
        if (apps.some((app) => app.name === body.name && app.namespace === body.namespace)) return send(response, 409, { message: "이미 등록된 앱입니다." });
        const app = { ...body, id: `app-${randomUUID()}`, createdAt: timestamp(), createdBy: "ui-preview" };
        apps.push(app); configurations.set(app.id, []); runs.set(app.id, []); executions.set(app.id, []);
        return send(response, 201, app);
    }
    const match = url.pathname.match(/^\/api\/v2\/deployment-applications\/([^/]+)\/(configurations|runs|executions|execution-targets)(?:\/([^/]+)\/actions)?$/);
    if (!match) return send(response, 404, { message: "Not found" });
    const [, id, collection, runId] = match;
    const app = apps.find((app) => app.id === id);
    if (!app) return send(response, 404, { message: "App not found" });
    if (["executions", "execution-targets"].includes(collection)) {
        if (app.kind !== "BATCH") return send(response, 400, { message: "배치 앱만 실행할 수 있습니다." });
        const history = executions.get(id) || [];
        const targets = templates.get(id) || [];
        if (request.method === "GET") return send(response, 200, collection === "executions" ? history : targets);
        if (collection !== "executions") return send(response, 405, { message: "Method not allowed" });
        const existing = history.find((execution) => execution.requestId === body.requestId);
        if (existing) return send(response, existing.cronJobName === body.cronJobName ? 202 : 409, existing);
        const target = targets.find((item) => item.name === body.cronJobName);
        if (!target) return send(response, 400, { message: "배포된 CronJob이 없습니다." });
        const execution = { id: `job-${randomUUID()}`, applicationId: id, cronJobName: target.name, requestId: body.requestId,
            jobName: `dd-job-${randomUUID()}`, images: { ...target.images }, status: "PENDING", requestedBy: "ui-preview", requestedAt: timestamp(), succeeded: 0, failed: 0 };
        history.push(execution); executions.set(id, history);
        later(execution, { status: "RUNNING", startedAt: timestamp(), active: 1 });
        setTimeout(() => Object.assign(execution, { status: "SUCCEEDED", completedAt: timestamp(), active: 0, succeeded: 1 }), 4000);
        return send(response, 202, execution);
    }
    const records = collection === "configurations" ? configurations.get(id) : runs.get(id);
    if (request.method === "GET") return send(response, 200, collection === "configurations" ? records.slice(-1) : records.map((run) => ({ ...run, configuration: run.configuration || configurations.get(id).find((config) => config.id === run.configurationId) })));
    const key = `${id}:${runId || collection}:${body.requestId}`;
    if (body.requestId && ids.has(key)) return send(response, 202, ids.get(key));
    if (collection === "configurations") {
        const config = { ...body, id: `cfg-${randomUUID()}`, applicationId: id, revision: (records.at(-1)?.revision || 0) + 1, batchTargets: body.batchTargets || [], canarySteps: body.canarySteps || [10, 50], savedBy: "ui-preview", savedAt: timestamp() };
        runs.get(id).forEach((run) => { run.configuration ||= records.find((value) => value.id === run.configurationId); });
        configurations.set(id, [config]); return send(response, 201, config);
    }
    if (!runId) {
        if (records.some((run) => !terminal(run))) return send(response, 409, { message: "진행 중인 배포가 있습니다." });
        const config = configurations.get(id).at(-1);
        if (!config || config.id !== body.configurationId) return send(response, 409, { message: "최신 설정만 배포할 수 있습니다." });
        const run = { ...body, id: `run-${randomUUID()}`, applicationId: id, kind: app.kind, orchestrator: app.orchestrator, webStrategy: config.webStrategy, batchMode: config.batchMode, status: "RUNNING", phase: "WAIT_READY", step: -1, requestedBy: "ui-preview", requestedAt: timestamp() };
        if (app.kind === "WEB" && config.webStrategy !== "ROLLING") run.result = { previewService: `${app.name}-preview`, previewPorts: [80], canaryWeight: 0, trafficMode: config.trafficAdapter ? "WEIGHTED" : config.webStrategy === "CANARY" ? "PREVIEW_ONLY" : null };
        run.configuration = config;
        records.push(run); ids.set(key, run);
        later(run, run.result ? { status: "AWAITING_APPROVAL", phase: "APPROVAL" } : { status: "SUCCEEDED" });
        if (app.kind === "BATCH") setTimeout(() => templates.set(id, (config.batchTargets.length ? config.batchTargets : [app.name])
            .map((name) => ({ name, images: { main: config.image } }))), 1800);
        return send(response, 202, run);
    }
    const run = records.find((run) => run.id === runId);
    if (!run) return send(response, 404, { message: "Run not found" });
    const config = run.configuration || configurations.get(id).find((config) => config.id === run.configurationId);
    if (body.action === "ADVANCE") {
        if (run.status !== "AWAITING_APPROVAL" || !config.trafficAdapter || run.step + 1 >= config.canarySteps.length) return send(response, 409, { message: "진행할 단계가 없습니다." });
        run.step++; run.status = "RUNNING"; run.phase = "ROUTE"; run.result.canaryWeight = config.canarySteps[run.step];
        later(run, { status: "AWAITING_APPROVAL", phase: "APPROVAL" });
    } else if (body.action === "PROMOTE") {
        if (run.status !== "AWAITING_APPROVAL" || (config.trafficAdapter && run.step !== config.canarySteps.length - 1)) return send(response, 409, { message: "아직 승격할 수 없습니다." });
        run.status = "PROMOTING"; run.phase = "SWITCH_WAIT";
        later(run, { status: "SUCCEEDED", phase: "SOURCE_TRAFFIC", result: { ...run.result, canaryWeight: 100 } });
    } else if (body.action === "ABORT" || body.action === "ROLLBACK") {
        if (body.action === "ROLLBACK" ? run.status !== "SUCCEEDED" || records.at(-1) !== run || records.some((item) => !terminal(item)) : terminal(run)) return send(response, 409, { message: "현재 상태에서는 처리할 수 없습니다." });
        run.status = "ABORTING"; run.phase = "RESTORE_WAIT";
        later(run, { status: body.action === "ABORT" ? "ABORTED" : "ROLLED_BACK" });
    } else return send(response, 400, { message: "Invalid action" });
    ids.set(key, run);
    return send(response, 202, run);
}

const server = http.createServer((request, response) => handle(request, response).catch((error) => send(response, error.code === "ENOENT" ? 404 : 500, { message: error.message })));
const port = Number(process.env.PORT || 4173);
server.listen(port, "127.0.0.1", () => console.log(`UI PREVIEW ONLY - no Kubernetes connection: http://127.0.0.1:${port}/deployments.html`));
