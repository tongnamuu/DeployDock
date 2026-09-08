const tokenKey = "deploydock.accessToken";
const form = document.querySelector("#crd-registration-form");
const registrations = document.querySelector("#custom-resource-registrations");
const adminContent = document.querySelector("#admin-content");
const accessMessage = document.querySelector("#access-message");
const refreshButton = document.querySelector("#refresh-button");
const logoutButton = document.querySelector("#logout-button");
const toast = document.querySelector("#toast");

let toastTimer;

logoutButton.addEventListener("click", () => {
    localStorage.removeItem(tokenKey);
    window.location.assign("/");
});
refreshButton.addEventListener("click", loadRegistrations);
form.addEventListener("submit", registerCustomResource);

async function initialize() {
    if (!localStorage.getItem(tokenKey)) {
        denyAccess("로그인 후 관리자 계정으로 접근해 주세요.");
        return;
    }
    await loadRegistrations();
}

async function loadRegistrations() {
    registrations.innerHTML = '<p class="mapping-empty">불러오는 중...</p>';
    try {
        const items = await api("/api/admin/permission-catalog/custom-resources");
        accessMessage.hidden = true;
        adminContent.hidden = false;
        renderRegistrations(items);
    } catch (error) {
        if (error.status === 401) localStorage.removeItem(tokenKey);
        if (error.status === 401 || error.status === 403) {
            denyAccess("이 페이지는 네임스페이스 생성 권한이 있는 관리자만 사용할 수 있습니다.");
        } else {
            denyAccess(error.message);
        }
    }
}

async function registerCustomResource(event) {
    event.preventDefault();
    const data = new FormData(form);
    const crdName = data.get("crdName").trim();
    const request = {
        displayName: data.get("displayName").trim() || null,
        allowedVerbs: data.getAll("allowedVerb"),
    };
    if (!request.allowedVerbs.length) {
        showToast("하나 이상의 동사를 선택해 주세요.", true);
        return;
    }
    await withButtonState(form.querySelector('button[type="submit"]'), async () => {
        await api(`/api/admin/permission-catalog/custom-resources/${encodeURIComponent(crdName)}`, {
            method: "PUT",
            body: JSON.stringify(request),
        });
        form.reset();
        await loadRegistrations();
        showToast(`${crdName} CRD를 등록했습니다.`);
    });
}

function renderRegistrations(items) {
    registrations.replaceChildren();
    if (!items.length) {
        appendMessage(registrations, "등록된 CRD가 없습니다.", "mapping-empty");
        return;
    }
    items.forEach((registration) => {
        const row = document.createElement("div");
        row.className = "registration-row";
        const description = document.createElement("div");
        const name = document.createElement("strong");
        name.textContent = registration.displayName;
        const detail = document.createElement("span");
        detail.textContent = `${registration.crdName} · ${registration.allowedVerbs.join(", ")}`;
        description.append(name, detail);
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "button button-danger";
        remove.textContent = "등록 해제";
        remove.addEventListener("click", () => unregisterCustomResource(registration.crdName, remove));
        row.append(description, remove);
        registrations.append(row);
    });
}

async function unregisterCustomResource(crdName, button) {
    if (!window.confirm(`${crdName} CRD 등록을 해제할까요?`)) return;
    await withButtonState(button, async () => {
        await api(`/api/admin/permission-catalog/custom-resources/${encodeURIComponent(crdName)}`, {
            method: "DELETE",
        });
        await loadRegistrations();
        showToast(`${crdName} CRD 등록을 해제했습니다.`);
    });
}

function denyAccess(message) {
    adminContent.hidden = true;
    accessMessage.hidden = false;
    accessMessage.textContent = message;
}

async function api(path, options = {}) {
    const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
    const token = localStorage.getItem(tokenKey);
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await fetch(path, { ...options, headers });
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
        const error = new Error(body?.message || `요청에 실패했습니다. (${response.status})`);
        error.status = response.status;
        throw error;
    }
    return body;
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

function appendMessage(parent, message, className) {
    const element = document.createElement("p");
    element.className = className;
    element.textContent = message;
    parent.append(element);
}

function showToast(message, error = false) {
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.toggle("error", error);
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 3500);
}

initialize();
