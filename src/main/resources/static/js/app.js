const tokenKey = "deploydock.accessToken";
const permissionVerbs = ["get", "list", "watch", "create", "update", "patch", "delete"];
const protectedNamespaces = new Set(["default", "deploydock-system", "local-path-storage"]);

const authPanel = document.querySelector("#auth-panel");
const workspace = document.querySelector("#workspace");
const loginForm = document.querySelector("#login-form");
const signupForm = document.querySelector("#signup-form");
const namespaceForm = document.querySelector("#namespace-form");
const namespaceCreatePanel = document.querySelector("#namespace-create-panel");
const namespaceList = document.querySelector("#namespace-list");
const logoutButton = document.querySelector("#logout-button");
const refreshButton = document.querySelector("#refresh-button");
const sessionUser = document.querySelector("#session-user");
const toast = document.querySelector("#toast");
const permissionAdmin = document.querySelector("#permission-admin");
const permissionNamespace = document.querySelector("#permission-namespace");
const permissionUser = document.querySelector("#permission-user");
const permissionMatrix = document.querySelector("#permission-matrix");
const memberMappings = document.querySelector("#member-mappings");
const savePermissionsButton = document.querySelector("#save-permissions-button");
const revokePermissionsButton = document.querySelector("#revoke-permissions-button");
const customResourcePageLink = document.querySelector("#custom-resource-page-link");
const adminCrdPageLink = document.querySelector("#admin-crd-page-link");

let toastTimer;
let namespaces = [];
let permissionCatalog = [];
let permissionMappings = [];

document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
        const loginSelected = tab.dataset.tab === "login";
        loginForm.hidden = !loginSelected;
        signupForm.hidden = loginSelected;
        document.querySelectorAll(".tab").forEach((item) => {
            const active = item === tab;
            item.classList.toggle("active", active);
            item.setAttribute("aria-selected", String(active));
        });
    });
});

loginForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    await authenticate(new FormData(loginForm));
});

signupForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const credentials = Object.fromEntries(new FormData(signupForm));
    await withSubmitState(signupForm, async () => {
        await api("/api/auth/signup", { method: "POST", body: JSON.stringify(credentials) }, false);
        showToast("계정을 만들었습니다. 로그인합니다.");
        await login(credentials);
    });
});

namespaceForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const name = new FormData(namespaceForm).get("name");
    await withSubmitState(namespaceForm, async () => {
        await api("/api/namespaces", { method: "POST", body: JSON.stringify({ name }) });
        namespaceForm.reset();
        showToast(`${name} 네임스페이스를 만들었습니다.`);
        await loadNamespaces();
    });
});

refreshButton.addEventListener("click", initializeWorkspace);
logoutButton.addEventListener("click", () => setSession(null));
permissionNamespace.addEventListener("change", loadMappings);
permissionUser.addEventListener("change", applySelectedMapping);
savePermissionsButton.addEventListener("click", savePermissions);
revokePermissionsButton.addEventListener("click", revokePermissions);

async function authenticate(formData) {
    await withSubmitState(loginForm, () => login(Object.fromEntries(formData)));
}

async function login(credentials) {
    const response = await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify(credentials),
    }, false);
    localStorage.setItem(tokenKey, response.accessToken);
    setSession(response.accessToken);
    await initializeWorkspace();
}

async function initializeWorkspace() {
    await loadNamespaces();
    await loadAdminTools();
}

async function loadNamespaces() {
    namespaceList.innerHTML = '<p class="empty-state">불러오는 중...</p>';
    try {
        namespaces = await api("/api/namespaces");
        renderNamespaces(namespaces);
        populateNamespaceSelector();
    } catch (error) {
        if (error.status === 401) setSession(null);
        namespaceList.innerHTML = '<p class="empty-state">목록을 불러오지 못했습니다.</p>';
        showToast(error.message, true);
    }
}

async function loadAdminTools() {
    try {
        const [catalog, users] = await Promise.all([
            api("/api/admin/permission-catalog"),
            api("/api/admin/users"),
        ]);
        permissionCatalog = catalog;
        namespaceCreatePanel.hidden = false;
        permissionAdmin.hidden = false;
        adminCrdPageLink.hidden = false;
        populateNamespaceSelector();
        populateUserSelector(users);
        renderPermissionMatrix();
        await loadMappings();
    } catch (error) {
        namespaceCreatePanel.hidden = true;
        permissionAdmin.hidden = true;
        adminCrdPageLink.hidden = true;
        if (error.status !== 403 && error.status !== 401) showToast(error.message, true);
    }
}

function renderNamespaces(items) {
    namespaceList.replaceChildren();
    if (!items.length) {
        appendEmpty(namespaceList, "접근 가능한 네임스페이스가 없습니다.", "empty-state");
        return;
    }
    items.forEach((namespace) => {
        const row = document.createElement("div");
        row.className = "namespace-row";
        const name = document.createElement("span");
        name.className = "namespace-name";
        name.textContent = namespace.name;
        const phase = document.createElement("span");
        phase.className = "namespace-phase";
        phase.textContent = namespace.phase || "Pending";
        row.append(name, phase);
        namespaceList.append(row);
    });
}

function populateNamespaceSelector() {
    const selected = permissionNamespace.value;
    permissionNamespace.replaceChildren();
    const manageable = namespaces.filter(
        (namespace) => !protectedNamespaces.has(namespace.name) && !namespace.name.startsWith("kube-"),
    );
    manageable.forEach((namespace) => permissionNamespace.append(createOption(namespace.name, namespace.name)));
    if (manageable.some((namespace) => namespace.name === selected)) permissionNamespace.value = selected;
}

function populateUserSelector(users) {
    const selected = permissionUser.value;
    permissionUser.replaceChildren();
    users.forEach((user) => permissionUser.append(createOption(user.username, user.username)));
    if (users.some((user) => user.username === selected)) permissionUser.value = selected;
}

function createOption(value, label) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    return option;
}

function renderPermissionMatrix() {
    permissionMatrix.replaceChildren();
    permissionCatalog.forEach((resource) => {
        const row = document.createElement("tr");
        row.dataset.apiGroup = resource.apiGroup;
        row.dataset.resource = resource.resource;

        const resourceCell = document.createElement("td");
        resourceCell.textContent = resource.displayName;
        const group = document.createElement("span");
        group.className = "resource-group";
        group.textContent = resource.apiGroup || "core";
        resourceCell.append(group);
        row.append(resourceCell);

        permissionVerbs.forEach((verb) => {
            const cell = document.createElement("td");
            if (resource.allowedVerbs.includes(verb)) {
                const checkbox = document.createElement("input");
                checkbox.type = "checkbox";
                checkbox.dataset.verb = verb;
                checkbox.setAttribute("aria-label", `${resource.displayName} ${verb}`);
                cell.append(checkbox);
            } else {
                cell.textContent = "—";
            }
            row.append(cell);
        });
        permissionMatrix.append(row);
    });
}

async function loadMappings() {
    const namespace = permissionNamespace.value;
    if (!namespace) {
        permissionMappings = [];
        renderMappings();
        applySelectedMapping();
        return;
    }
    try {
        permissionMappings = await api(`/api/admin/namespaces/${encodeURIComponent(namespace)}/members`);
        renderMappings();
        applySelectedMapping();
    } catch (error) {
        showToast(error.message, true);
    }
}

function renderMappings() {
    memberMappings.replaceChildren();
    if (!permissionMappings.length) {
        appendEmpty(memberMappings, "아직 매핑된 멤버가 없습니다.", "mapping-empty");
        return;
    }
    permissionMappings.forEach((mapping) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "mapping-button";
        button.classList.toggle("active", mapping.username === permissionUser.value);
        button.textContent = mapping.username;
        const detail = document.createElement("span");
        const verbCount = mapping.permissions.reduce((total, permission) => total + permission.verbs.length, 0);
        detail.textContent = `${mapping.permissions.length}개 리소스 · ${verbCount}개 권한`;
        button.append(detail);
        button.addEventListener("click", () => {
            permissionUser.value = mapping.username;
            applySelectedMapping();
        });
        memberMappings.append(button);
    });
}

function applySelectedMapping() {
    clearPermissionMatrix();
    const mapping = permissionMappings.find((item) => item.username === permissionUser.value);
    mapping?.permissions.forEach((permission) => {
        const row = findPermissionRow(permission.apiGroup, permission.resource);
        permission.verbs.forEach((verb) => {
            const checkbox = row?.querySelector(`input[data-verb="${verb}"]`);
            if (checkbox) checkbox.checked = true;
        });
    });
    revokePermissionsButton.disabled = !mapping;
    renderMappings();
}

function clearPermissionMatrix() {
    permissionMatrix.querySelectorAll('input[type="checkbox"]').forEach((checkbox) => {
        checkbox.checked = false;
    });
}

function findPermissionRow(apiGroup, resource) {
    return [...permissionMatrix.querySelectorAll("tr")].find(
        (row) => row.dataset.apiGroup === apiGroup && row.dataset.resource === resource,
    );
}

function collectPermissions() {
    return [...permissionMatrix.querySelectorAll("tr")].map((row) => ({
        apiGroup: row.dataset.apiGroup,
        resource: row.dataset.resource,
        verbs: [...row.querySelectorAll('input[type="checkbox"]:checked')].map((checkbox) => checkbox.dataset.verb),
    })).filter((permission) => permission.verbs.length > 0);
}

async function savePermissions() {
    const namespace = permissionNamespace.value;
    const username = permissionUser.value;
    if (!namespace || !username) return showToast("네임스페이스와 멤버를 선택해 주세요.", true);
    await withButtonState(savePermissionsButton, async () => {
        await api(`/api/admin/namespaces/${encodeURIComponent(namespace)}/members/${encodeURIComponent(username)}`, {
            method: "PUT",
            body: JSON.stringify({ permissions: collectPermissions() }),
        });
        await loadMappings();
        showToast(`${username} 멤버의 권한을 저장했습니다.`);
    });
}

async function revokePermissions() {
    const namespace = permissionNamespace.value;
    const username = permissionUser.value;
    if (!namespace || !username) return;
    if (!window.confirm(`${username} 멤버의 ${namespace} 권한을 모두 회수할까요?`)) return;
    await withButtonState(revokePermissionsButton, async () => {
        await api(`/api/admin/namespaces/${encodeURIComponent(namespace)}/members/${encodeURIComponent(username)}`, {
            method: "DELETE",
        });
        await loadMappings();
        showToast(`${username} 멤버의 권한을 모두 회수했습니다.`);
    });
    applySelectedMapping();
}

function setSession(token) {
    if (!token) {
        localStorage.removeItem(tokenKey);
        authPanel.hidden = false;
        workspace.hidden = true;
        namespaceCreatePanel.hidden = true;
        permissionAdmin.hidden = true;
        customResourcePageLink.hidden = true;
        adminCrdPageLink.hidden = true;
        logoutButton.hidden = true;
        sessionUser.hidden = true;
        return;
    }
    const encodedPayload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const paddedPayload = encodedPayload.padEnd(Math.ceil(encodedPayload.length / 4) * 4, "=");
    const payload = JSON.parse(atob(paddedPayload));
    sessionUser.textContent = payload.username || payload.sub;
    sessionUser.hidden = false;
    logoutButton.hidden = false;
    customResourcePageLink.hidden = false;
    authPanel.hidden = true;
    workspace.hidden = false;
}

async function api(path, options = {}, authenticated = true) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    if (authenticated) {
        const token = localStorage.getItem(tokenKey);
        if (token) headers.Authorization = `Bearer ${token}`;
    }
    const response = await fetch(path, { ...options, headers });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        const error = new Error(body?.message || `요청에 실패했습니다. (${response.status})`);
        error.status = response.status;
        throw error;
    }
    return body;
}

async function withSubmitState(form, action) {
    const button = form.querySelector('button[type="submit"]');
    await withButtonState(button, action);
}

async function withButtonState(button, action) {
    button.disabled = true;
    try {
        await action();
    } catch (error) {
        showToast(error.message, true);
    } finally {
        button.disabled = false;
    }
}

function appendEmpty(parent, message, className) {
    const empty = document.createElement("p");
    empty.className = className;
    empty.textContent = message;
    parent.append(empty);
}

function showToast(message, error = false) {
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.toggle("error", error);
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 3500);
}

const savedToken = localStorage.getItem(tokenKey);
if (savedToken) {
    try {
        setSession(savedToken);
        initializeWorkspace();
    } catch {
        setSession(null);
    }
}
