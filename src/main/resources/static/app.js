const state = {
    request: null
}

const elements = {
    statusDot: document.getElementById("status-dot"),
    systemStatus: document.getElementById("system-status"),
    healthDetail: document.getElementById("health-detail"),
    form: document.getElementById("transfer-form"),
    apiKey: document.getElementById("api-key"),
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
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, value => {
        const random = Math.floor(Math.random() * 16)
        const result = value === "x" ? random : (random & 0x3) | 0x8
        return result.toString(16)
    })
}

function setAccountId() {
    elements.accountId.value = randomId()
}

async function checkHealth() {
    const startedAt = performance.now()
    try {
        const response = await fetch("/actuator/health", {headers: {Accept: "application/json"}})
        const body = await response.json()
        const elapsed = Math.round(performance.now() - startedAt)
        if (!response.ok || body.status !== "UP") {
            throw new Error("Health endpoint is not UP")
        }
        elements.statusDot.classList.add("online")
        elements.systemStatus.textContent = "Live system operational"
        elements.healthDetail.textContent = `Health check completed in ${elapsed} ms.`
    } catch (error) {
        elements.statusDot.classList.add("offline")
        elements.systemStatus.textContent = "System unavailable"
        elements.healthDetail.textContent = error.message
    }
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
    return {
        idempotencyKey: `web-${Date.now()}-${randomId()}`,
        body: {
            accountId: elements.accountId.value.trim(),
            type: elements.transferType.value,
            amount: Number(elements.amount.value),
            currency: elements.currency.value.trim().toUpperCase()
        }
    }
}

function showResponse(response, replayed, payload) {
    elements.responseStatus.textContent = `HTTP ${response.status}`
    elements.replayStatus.textContent = replayed === null ? "Replay unknown" : `Replayed ${replayed}`
    elements.responseOutput.textContent = JSON.stringify(payload, null, 2)
}

function showLedger(entries) {
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
    entries.forEach(entry => {
        const row = document.createElement("tr")
        const values = [entry.accountCode, entry.entryType, entry.amount, entry.currency]
        values.forEach(value => {
            const cell = document.createElement("td")
            cell.textContent = String(value)
            row.appendChild(cell)
        })
        elements.ledgerBody.appendChild(row)
    })
}

async function loadLedger(transferId, apiKey) {
    const response = await fetch(`/api/v1/transfers/${encodeURIComponent(transferId)}/ledger-entries`, {
        headers: {"X-Portfolio-Key": apiKey, Accept: "application/json"}
    })
    if (!response.ok) {
        throw new Error(`Ledger request failed with HTTP ${response.status}`)
    }
    showLedger(await response.json())
}

async function sendTransfer(replay) {
    const apiKey = elements.apiKey.value
    if (!apiKey) {
        elements.labMessage.textContent = "Enter the private portfolio API key."
        elements.apiKey.focus()
        return
    }
    if (!elements.form.reportValidity()) {
        return
    }
    if (!replay) {
        state.request = requestFromForm()
    }
    if (!state.request) {
        elements.labMessage.textContent = "Create a transfer before replaying it."
        return
    }
    setBusy(true)
    try {
        const response = await fetch("/api/v1/transfers", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "X-Portfolio-Key": apiKey,
                "Idempotency-Key": state.request.idempotencyKey
            },
            body: JSON.stringify(state.request.body)
        })
        const raw = await response.text()
        let payload
        try {
            payload = JSON.parse(raw)
        } catch (error) {
            payload = {detail: raw || error.message}
        }
        const replayed = response.headers.get("Idempotency-Replayed")
        showResponse(response, replayed, payload)
        if (!response.ok) {
            throw new Error(payload.detail || `Request failed with HTTP ${response.status}`)
        }
        elements.labMessage.textContent = replayed === "true" ? "Original transfer returned safely." : "New transfer committed."
        await loadLedger(payload.id, apiKey)
    } catch (error) {
        elements.labMessage.textContent = error.message
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
