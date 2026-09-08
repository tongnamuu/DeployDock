const tokenKey = "deploydock.accessToken";
const form = document.querySelector("#custom-resource-form");
const resourceSelection = document.querySelector("#resource-selection");
const createContent = document.querySelector("#create-content");
const accessMessage = document.querySelector("#access-message");
const creationResult = document.querySelector("#creation-result");
const refreshButton = document.querySelector("#refresh-button");
const logoutButton = document.querySelector("#logout-button");
const sessionUser = document.querySelector("#session-user");
const toast = document.querySelector("#toast");

let creatableResources = [];
let toastTimer;

logoutButton.addEventListener("click", () => {
    localStorage.removeItem(tokenKey);
    window.location.assign("/");
});
refreshButton.addEventListener("click", loadCreatableResources);
form.addEventListener("submit", createCustomResource);

async function initialize() {
    const token = localStorage.getItem(tokenKey);
    if (!token) {
        showAccessMessage("로그인 후 이용해 주세요.");
        return;
    }
    try {
        const encodedPayload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
        const payload = JSON.parse(atob(encodedPayload.padEnd(Math.ceil(encodedPayload.length / 4) * 4, "=")));
        sessionUser.textContent = payload.username || payload.sub;
        sessionUser.hidden = false;
    } catch {
        localStorage.removeItem(tokenKey);
        showAccessMessage("세션을 확인할 수 없습니다. 다시 로그인해 주세요.");
        return;
    }
    await loadCreatableResources();
}

async function loadCreatableResources() {
    try {
        creatableResources = await api("/api/custom-resources/creatable");
        if (!creatableResources.length) {
            createContent.hidden = true;
            showAccessMessage("현재 계정에 생성 권한이 부여된 등록 CRD가 없습니다.");
            return;
        }
        populateResourceSelection();
        accessMessage.hidden = true;
        createContent.hidden = false;
    } catch (error) {
        if (error.status === 401) localStorage.removeItem(tokenKey);
        createContent.hidden = true;
        showAccessMessage(error.status === 401 ? "세션이 만료되었습니다. 다시 로그인해 주세요." : error.message);
    }
}

function populateResourceSelection() {
    resourceSelection.replaceChildren();
    creatableResources.forEach((resource, index) => {
        const option = document.createElement("option");
        option.value = String(index);
        option.textContent = `${resource.namespace} / ${resource.displayName} (${resource.apiVersion})`;
        resourceSelection.append(option);
    });
}

async function createCustomResource(event) {
    event.preventDefault();
    const selected = creatableResources[Number(resourceSelection.value)];
    if (!selected) {
        showToast("생성할 리소스를 선택해 주세요.", true);
        return;
    }
    const data = new FormData(form);
    let spec;
    try {
        spec = JSON.parse(data.get("spec"));
        if (spec === null || Array.isArray(spec) || typeof spec !== "object") throw new Error();
    } catch {
        showToast("spec에는 올바른 JSON 객체를 입력해 주세요.", true);
        return;
    }
    await withButtonState(form.querySelector('button[type="submit"]'), async () => {
        const created = await api(
            `/api/custom-resources/${encodeURIComponent(selected.namespace)}/${encodeURIComponent(selected.crdName)}`,
            {
                method: "POST",
                body: JSON.stringify({ name: data.get("name"), spec }),
            },
        );
        creationResult.textContent = JSON.stringify(created, null, 2);
        showToast(`${created.namespace}/${created.name} 리소스를 만들었습니다.`);
    });
}

function showAccessMessage(message) {
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

function showToast(message, error = false) {
    clearTimeout(toastTimer);
    toast.textContent = message;
    toast.classList.toggle("error", error);
    toast.hidden = false;
    toastTimer = setTimeout(() => { toast.hidden = true; }, 3500);
}

initialize();
