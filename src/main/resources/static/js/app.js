const tokenKey = "deploydock.accessToken";

const authPanel = document.querySelector("#auth-panel");
const workspace = document.querySelector("#workspace");
const loginForm = document.querySelector("#login-form");
const signupForm = document.querySelector("#signup-form");
const namespaceForm = document.querySelector("#namespace-form");
const namespaceList = document.querySelector("#namespace-list");
const logoutButton = document.querySelector("#logout-button");
const refreshButton = document.querySelector("#refresh-button");
const sessionUser = document.querySelector("#session-user");
const toast = document.querySelector("#toast");

let toastTimer;

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
    const data = new FormData(signupForm);
    const credentials = Object.fromEntries(data);
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

refreshButton.addEventListener("click", loadNamespaces);
logoutButton.addEventListener("click", () => setSession(null));

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
    await loadNamespaces();
}

async function loadNamespaces() {
    namespaceList.innerHTML = '<p class="empty-state">불러오는 중...</p>';
    try {
        const namespaces = await api("/api/namespaces");
        renderNamespaces(namespaces);
    } catch (error) {
        if (error.status === 401) setSession(null);
        namespaceList.innerHTML = '<p class="empty-state">목록을 불러오지 못했습니다.</p>';
        showToast(error.message, true);
    }
}

function renderNamespaces(namespaces) {
    namespaceList.replaceChildren();
    if (!namespaces.length) {
        const empty = document.createElement("p");
        empty.className = "empty-state";
        empty.textContent = "접근 가능한 네임스페이스가 없습니다.";
        namespaceList.append(empty);
        return;
    }
    namespaces.forEach((namespace) => {
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

function setSession(token) {
    if (!token) {
        localStorage.removeItem(tokenKey);
        authPanel.hidden = false;
        workspace.hidden = true;
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

const savedToken = localStorage.getItem(tokenKey);
if (savedToken) {
    try {
        setSession(savedToken);
        loadNamespaces();
    } catch {
        setSession(null);
    }
}
