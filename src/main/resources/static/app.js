const state = {
    request: null
}

const REQUEST_TIMEOUT_MS = 110000
const EXACT_AMOUNT = /^(?:0\.(?:0[1-9]|[1-9][0-9]?)|[1-9][0-9]{0,16}(?:\.[0-9]{1,2})?)$/

const elements = {
    statusDot: document.getElementById("status-dot"),
    systemStatus: document.getElementById("system-status"),
    healthDetail: document.getElementById("health-detail"),
    form: document.getElementById("transfer-form"),
    accountId: document.getElementById("account-id"),
    transferType: document.getElementById("transfer-type"),
    amount: document.getElementById("amount"),
    currency: document.getElementById("currency"),
    newAccount: document.getElementById("new-account"),
    replayRequest: document.getElementById("replay-request"),
    labMessage: document.getElementById("lab-message"),
    responseStatus: document.getElementById("response-status"),
    replayStatus: document.getElementById("replay-status"),
    responseOutput: document.getElementById("response-output"),
    ledgerBody: document.getElementById("ledger-body")
}

function randomId() {
    if (window.crypto && typeof window.crypto.randomUUID === "function") {
        return window.crypto.randomUUID()
    }
    const bytes = new Uint8Array(16)
    window.crypto.getRandomValues(bytes)
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("")
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function setAccountId() {
    elements.accountId.value = randomId()
}

async function checkHealth() {
    const startedAt = performance.now()
    try {
        const response = await fetchWithTimeout("/actuator/health", {
            headers: {Accept: "application/json"}
        })
        const body = await response.json()
        const elapsed = Math.round(performance.now() - startedAt)
        if (!response.ok || body.status !== "UP") {
            throw new Error("Health endpoint is not UP")
        }
        elements.statusDot.classList.remove("offline")
        elements.statusDot.classList.add("online")
        elements.systemStatus.textContent = "Live system operational"
        elements.healthDetail.textContent = `Health check completed in ${elapsed} ms.`
    } catch (error) {
        elements.statusDot.classList.remove("online")
        elements.statusDot.classList.add("offline")
        elements.systemStatus.textContent = "System unavailable"
        elements.healthDetail.textContent = friendlyError(error)
    }
}

async function fetchWithTimeout(url, options = {}) {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
    try {
        return await fetch(url, {...options, signal: controller.signal})
    } finally {
        window.clearTimeout(timeout)
    }
}

function friendlyError(error) {
    return error && error.name === "AbortError"
        ? "The request timed out while the free service was waking. Try again."
        : error.message
}

function setBusy(busy) {
    const submit = elements.form.querySelector("button[type='submit']")
    submit.disabled = busy
    elements.replayRequest.disabled = busy || state.request === null
    if (busy) {
        elements.labMessage.textContent = "Sending request to LedgerRail Core."
    }
}

function requestFromForm() {
    const amount = elements.amount.value.trim()
    if (!EXACT_AMOUNT.test(amount)) {
        throw new Error("Enter a positive amount with at most 17 whole-number digits and two decimal places.")
    }
    return {
        idempotencyKey: `web-${Date.now()}-${randomId()}`,
        body: {
            accountId: elements.accountId.value.trim(),
            type: elements.transferType.value,
            amount,
            currency: elements.currency.value.trim().toUpperCase()
        }
    }
}

function transferJson(body) {
    return `{"accountId":${JSON.stringify(body.accountId)},"type":${JSON.stringify(body.type)},` +
        `"amount":${body.amount},"currency":${JSON.stringify(body.currency)}}`
}

function exactAmounts(raw) {
    return Array.from(raw.matchAll(/"amount"\s*:\s*(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g), match => match[1])
}

function formattedPayload(raw, payload) {
    const amounts = exactAmounts(raw)
    let amountIndex = 0
    return JSON.stringify(payload, null, 2).replace(
        /("amount":\s*)-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g,
        (match, prefix) => `${prefix}${amounts[amountIndex++] ?? match.slice(prefix.length)}`
    )
}

function showResponse(response, replayed, payload, raw) {
    elements.responseStatus.textContent = `HTTP ${response.status}`
    elements.replayStatus.textContent = replayed === null ? "Replay unknown" : `Replayed ${replayed}`
    elements.responseOutput.textContent = formattedPayload(raw, payload)
}

function showLedger(entries, amounts = []) {
    elements.ledgerBody.replaceChildren()
    if (!Array.isArray(entries) || entries.length === 0) {
        const row = document.createElement("tr")
        const cell = document.createElement("td")
        cell.colSpan = 4
        cell.textContent = "No ledger entries returned."
        row.appendChild(cell)
        elements.ledgerBody.appendChild(row)
        return
    }
    entries.forEach((entry, index) => {
        const row = document.createElement("tr")
        const values = [entry.accountCode, entry.entryType, amounts[index] ?? entry.amount, entry.currency]
        values.forEach(value => {
            const cell = document.createElement("td")
            cell.textContent = String(value)
            row.appendChild(cell)
        })
        elements.ledgerBody.appendChild(row)
    })
}

async function loadLedger(transferId) {
    const response = await fetchWithTimeout(`/api/v1/transfers/${encodeURIComponent(transferId)}/ledger-entries`, {
        headers: {Accept: "application/json"}
    })
    const raw = await response.text()
    if (!response.ok) {
        throw new Error(`Ledger request failed with HTTP ${response.status}`)
    }
    showLedger(JSON.parse(raw), exactAmounts(raw))
}

async function sendTransfer(replay) {
    if (!elements.form.reportValidity()) {
        return
    }
    if (!replay) {
        try {
            state.request = requestFromForm()
        } catch (error) {
            elements.labMessage.textContent = friendlyError(error)
            return
        }
    }
    if (!state.request) {
        elements.labMessage.textContent = "Create a transfer before replaying it."
        return
    }
    setBusy(true)
    try {
        const response = await fetchWithTimeout("/api/v1/transfers", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Idempotency-Key": state.request.idempotencyKey
            },
            body: transferJson(state.request.body)
        })
        const raw = await response.text()
        let payload
        try {
            payload = JSON.parse(raw)
        } catch (error) {
            payload = {detail: raw || error.message}
        }
        const replayed = response.headers.get("Idempotency-Replayed")
        showResponse(response, replayed, payload, raw)
        if (!response.ok) {
            throw new Error(payload.detail || `Request failed with HTTP ${response.status}`)
        }
        elements.labMessage.textContent = replayed === "true" ? "Original transfer returned safely." : "New transfer committed."
        await loadLedger(payload.id)
    } catch (error) {
        elements.labMessage.textContent = friendlyError(error)
    } finally {
        setBusy(false)
    }
}

elements.form.addEventListener("submit", event => {
    event.preventDefault()
    sendTransfer(false)
})

elements.replayRequest.addEventListener("click", () => sendTransfer(true))
elements.newAccount.addEventListener("click", setAccountId)
elements.currency.addEventListener("input", () => {
    elements.currency.value = elements.currency.value.toUpperCase()
})

setAccountId()
checkHealth()
