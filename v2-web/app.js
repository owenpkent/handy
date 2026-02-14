/**
 * Handy V2 Control App
 */

const api = new HandyV2API();

// Debug elements
const debugLog = document.getElementById('debugLog');
const dbgMode = document.getElementById('dbgMode');
const dbgSpeed = document.getElementById('dbgSpeed');
const dbgStroke = document.getElementById('dbgStroke');
const dbgState = document.getElementById('dbgState');
const clearDebugBtn = document.getElementById('clearDebugBtn');

// Debug logging
api.onLog = (entry) => {
    const time = new Date().toLocaleTimeString();
    const div = document.createElement('div');
    div.className = 'log-entry';
    
    if (entry.type === 'request') {
        div.innerHTML = `<span class="log-time">${time}</span><span class="log-method">${entry.method}</span><span class="log-endpoint">${entry.endpoint}</span>`;
        if (entry.body) {
            div.innerHTML += `<span class="log-body">→ ${JSON.stringify(entry.body)}</span>`;
        }
    } else if (entry.type === 'response') {
        div.innerHTML = `<span class="log-time">${time}</span><span class="log-response">← ${JSON.stringify(entry.data)}</span>`;
        updateDebugState(entry.endpoint, entry.data);
    } else if (entry.type === 'error') {
        div.innerHTML = `<span class="log-time">${time}</span><span class="log-error">✗ ${entry.error}</span>`;
    }
    
    debugLog.appendChild(div);
    debugLog.scrollTop = debugLog.scrollHeight;
};

function updateDebugState(endpoint, data) {
    if (endpoint === '/mode') {
        const modes = ['HAMP', 'HSSP', 'HDSP', 'MAINT', 'HBSP'];
        dbgMode.textContent = modes[data.mode] || data.mode;
    }
    if (endpoint === '/hamp/velocity' && data.velocity !== undefined) {
        dbgSpeed.textContent = data.velocity + '%';
    }
    if (endpoint === '/slide') {
        dbgStroke.textContent = `${data.min || 0}-${data.max || 100}%`;
    }
    if (endpoint === '/hamp/state') {
        dbgState.textContent = data.state === 2 ? 'MOVING' : 'STOPPED';
    }
    if (endpoint === '/hamp/start') {
        dbgState.textContent = 'MOVING';
    }
    if (endpoint === '/hamp/stop') {
        dbgState.textContent = 'STOPPED';
    }
}

function clearDebug() {
    debugLog.innerHTML = '';
}

// Speed control
const speedMultiplierSlider = document.getElementById('speedMultiplier');
const speedMultiplierValue = document.getElementById('speedMultiplierValue');
const scaledSlowSpan = document.getElementById('scaledSlow');
let SPEED_MULTIPLIER = 100; // Percentage (10-100)

// Safety settings
const safetyMaxSlider = document.getElementById('safetyMax');
const safetyMaxValue = document.getElementById('safetyMaxValue');
let SAFETY_MAX = 90; // Global safety limit

// DOM Elements
const setupPanel = document.getElementById('setupPanel');
const controlPanel = document.getElementById('controlPanel');
const connectionKeyInput = document.getElementById('connectionKey');
const connectBtn = document.getElementById('connectBtn');
const disconnectBtn = document.getElementById('disconnectBtn');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const strokeMinSlider = document.getElementById('strokeMin');
const strokeMaxSlider = document.getElementById('strokeMax');
const strokeMinValue = document.getElementById('strokeMinValue');
const strokeMaxValue = document.getElementById('strokeMaxValue');
const presetBtns = document.querySelectorAll('.preset-btn');
const tryHrppBtn = document.getElementById('tryHrppBtn');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const batteryStatus = document.getElementById('batteryStatus');
const feedback = document.getElementById('feedback');

// Preset definitions
const SPEED_VALUES = {
    slow: 25,
    medium: 50,
    fast: 80
};

let currentBaseSpeed = 50; // Track the unscaled base speed

const POSITION_VALUES = {
    top: { min: 50, max: 90 },
    middle: { min: 30, max: 70 },
    bottom: { min: 10, max: 50 },
    full: { min: 0, max: 100 },
    natural: { min: 20, max: 80 }
};

let feedbackTimeout = null;

function showFeedback(message, type = 'info') {
    feedback.textContent = message;
    feedback.className = `feedback ${type}`;
    feedback.classList.remove('hidden');
    
    if (feedbackTimeout) clearTimeout(feedbackTimeout);
    feedbackTimeout = setTimeout(() => {
        feedback.classList.add('hidden');
    }, 3000);
}

function setStatus(status) {
    statusDot.className = 'status-dot';
    if (status === 'connected') {
        statusDot.classList.add('connected');
        statusText.textContent = 'Connected';
    } else if (status === 'connecting') {
        statusDot.classList.add('connecting');
        statusText.textContent = 'Connecting...';
    } else {
        statusText.textContent = 'Disconnected';
    }
}

function showSetup() {
    setupPanel.classList.remove('hidden');
    controlPanel.classList.add('hidden');
    setStatus('disconnected');
}

function showControls() {
    setupPanel.classList.add('hidden');
    controlPanel.classList.remove('hidden');
    setStatus('connected');
    // Start battery polling
    fetchBattery();
    startBatteryPolling();
}

let batteryInterval = null;

function startBatteryPolling() {
    if (batteryInterval) clearInterval(batteryInterval);
    batteryInterval = setInterval(fetchBattery, 30000); // Every 30 seconds
}

function stopBatteryPolling() {
    if (batteryInterval) {
        clearInterval(batteryInterval);
        batteryInterval = null;
    }
}

async function fetchBattery() {
    try {
        // Try multiple endpoints for battery info
        let battery = null;
        
        // Try /info endpoint
        try {
            const info = await api.request('/info');
            if (info.battery !== undefined) battery = info.battery;
        } catch (e) {}
        
        // Try /settings endpoint
        if (battery === null) {
            try {
                const settings = await api.request('/settings');
                if (settings.battery !== undefined) battery = settings.battery;
            } catch (e) {}
        }
        
        // Try /status endpoint
        if (battery === null) {
            try {
                const status = await api.request('/status');
                if (status.battery !== undefined) battery = status.battery;
            } catch (e) {}
        }
        
        if (battery !== null) {
            updateBatteryDisplay(battery);
        } else {
            // Battery not available in API
            batteryStatus.classList.add('hidden');
        }
    } catch (e) {
        batteryStatus.classList.add('hidden');
    }
}

function updateBatteryDisplay(level) {
    batteryStatus.classList.remove('hidden');
    batteryStatus.textContent = `🔋 ${level}%`;
    if (level < 20) {
        batteryStatus.classList.add('low');
    } else {
        batteryStatus.classList.remove('low');
    }
}

async function connect() {
    const key = connectionKeyInput.value.trim();
    if (!key) {
        showFeedback('Enter your connection key', 'error');
        return;
    }

    connectBtn.disabled = true;
    connectBtn.textContent = 'Connecting...';
    setStatus('connecting');

    try {
        api.setConnectionKey(key);
        const info = await api.connect();
        
        showControls();
        showFeedback(`Connected! Firmware: ${info.fwVersion}`, 'success');
    } catch (error) {
        showFeedback(error.message, 'error');
        setStatus('disconnected');
    } finally {
        connectBtn.disabled = false;
        connectBtn.textContent = 'Connect';
    }
}

async function disconnect() {
    stopBatteryPolling();
    batteryStatus.classList.add('hidden');
    try {
        await api.disconnect();
    } catch (e) {
        // Ignore
    }
    api.clearConnectionKey();
    connectionKeyInput.value = '';
    showSetup();
    showFeedback('Disconnected', 'info');
}

async function start() {
    startBtn.disabled = true;
    try {
        await api.start();
        showFeedback('Started', 'success');
    } catch (error) {
        showFeedback(error.message, 'error');
    } finally {
        startBtn.disabled = false;
    }
}

async function stop() {
    stopBtn.disabled = true;
    try {
        await api.stop();
        clearActivePreset();
        showFeedback('Stopped', 'success');
    } catch (error) {
        showFeedback(error.message, 'error');
    } finally {
        stopBtn.disabled = false;
    }
}

async function setStrokeRange(min, max) {
    // Always apply safety limit
    const safeMax = applySafetyLimit(max);
    try {
        await api.setStrokeRange(min, safeMax);
    } catch (error) {
        showFeedback(error.message, 'error');
    }
}

function applySafetyLimit(max) {
    // Enforce safety max
    return Math.min(max, SAFETY_MAX);
}

function applySpeedMultiplier(speed) {
    // Scale speed by multiplier (10-100%)
    return Math.round(speed * (SPEED_MULTIPLIER / 100));
}

function updateSpeedPreview() {
    // Show what "slow" will actually be
    const scaledSlow = applySpeedMultiplier(SPEED_VALUES.slow);
    scaledSlowSpan.textContent = scaledSlow;
}

async function applyPreset(speed, position, btn) {
    const speedVal = SPEED_VALUES[speed];
    const posVal = POSITION_VALUES[position];
    
    if (!speedVal || !posVal) return;
    
    // Apply safety limit to max
    const safeMax = applySafetyLimit(posVal.max);
    
    // Update sliders to match preset (with safety limit)
    strokeMinSlider.value = posVal.min;
    strokeMaxSlider.value = safeMax;
    strokeMinValue.textContent = posVal.min;
    strokeMaxValue.textContent = safeMax;
    
    // Mark button as active
    clearActivePreset();
    btn.classList.add('active');
    
    // Store base speed and apply multiplier
    currentBaseSpeed = speedVal;
    const scaledSpeed = applySpeedMultiplier(speedVal);
    
    try {
        // Set stroke range first (with safety limit applied)
        await api.setStrokeRange(posVal.min, safeMax);
        // Start motion
        await api.start();
        // Set speed AFTER starting (with multiplier applied)
        await api.setSpeed(scaledSpeed);
        showFeedback(`${speed} ${position} (${scaledSpeed}%)`, 'success');
    } catch (error) {
        showFeedback(error.message, 'error');
    }
}

function clearActivePreset() {
    presetBtns.forEach(b => b.classList.remove('active'));
}

// HRPP Experiment - try HSSP mode and explore endpoints
async function tryHrppMode() {
    showFeedback('Trying HSSP mode...', 'info');
    
    try {
        // Set mode to HSSP (mode 1) - this is what HRPP might use internally
        await api.request('/mode', {
            method: 'PUT',
            body: JSON.stringify({ mode: 1 })
        });
        
        // Try HSSP endpoints
        const hsspEndpoints = [
            '/hssp',
            '/hssp/state',
            '/hssp/loop',
            '/hssp/setup',
            '/hssp/play',
            '/hssp/stop'
        ];
        
        for (const endpoint of hsspEndpoints) {
            try {
                const result = await api.request(endpoint);
                console.log(`${endpoint} worked:`, result);
            } catch (e) {
                // Logged via onLog
            }
        }
        
        // Try to get current state
        try {
            const state = await api.request('/status');
            console.log('Status:', state);
        } catch (e) {}
        
        // Try HBSP (mode 4) as well
        await api.request('/mode', {
            method: 'PUT',
            body: JSON.stringify({ mode: 4 })
        });
        
        // HBSP specific endpoints
        const hbspEndpoints = [
            '/hbsp',
            '/hbsp/state',
            '/hbsp/setup',
            '/hbsp/play',
            '/hbsp/stop',
            '/hbsp/patterns'
        ];
        
        for (const endpoint of hbspEndpoints) {
            try {
                const result = await api.request(endpoint);
                console.log(`${endpoint} worked:`, result);
            } catch (e) {
                // Logged via onLog
            }
        }
        
        showFeedback('Check debug log for results', 'success');
        
    } catch (error) {
        showFeedback(`Experiment: ${error.message}`, 'error');
    }
    
    // Switch back to HAMP mode
    try {
        await api.setHAMPMode();
    } catch (e) {
        // Ignore
    }
}

// Event Listeners
connectBtn.addEventListener('click', connect);
disconnectBtn.addEventListener('click', disconnect);
startBtn.addEventListener('click', start);
stopBtn.addEventListener('click', stop);
tryHrppBtn.addEventListener('click', tryHrppMode);

// Stroke sliders
strokeMinSlider.addEventListener('input', (e) => {
    strokeMinValue.textContent = e.target.value;
    // Ensure min doesn't exceed max
    if (parseInt(e.target.value) >= parseInt(strokeMaxSlider.value)) {
        strokeMaxSlider.value = parseInt(e.target.value) + 10;
        strokeMaxValue.textContent = strokeMaxSlider.value;
    }
});

strokeMinSlider.addEventListener('change', (e) => {
    clearActivePreset();
    setStrokeRange(parseInt(strokeMinSlider.value), parseInt(strokeMaxSlider.value));
});

strokeMaxSlider.addEventListener('input', (e) => {
    strokeMaxValue.textContent = e.target.value;
    // Ensure max doesn't go below min
    if (parseInt(e.target.value) <= parseInt(strokeMinSlider.value)) {
        strokeMinSlider.value = parseInt(e.target.value) - 10;
        strokeMinValue.textContent = strokeMinSlider.value;
    }
});

strokeMaxSlider.addEventListener('change', (e) => {
    clearActivePreset();
    setStrokeRange(parseInt(strokeMinSlider.value), parseInt(strokeMaxSlider.value));
});

// Preset buttons
presetBtns.forEach(btn => {
    btn.addEventListener('click', () => {
        const speed = btn.dataset.speed;
        const position = btn.dataset.position;
        applyPreset(speed, position, btn);
    });
});

// Keyboard shortcuts
document.addEventListener('keydown', (e) => {
    if (controlPanel.classList.contains('hidden')) return;
    
    if (e.key === ' ' || e.key === 'Escape') {
        e.preventDefault();
        stop();
    }
});

// Clear debug button
clearDebugBtn.addEventListener('click', clearDebug);

// Speed multiplier slider
speedMultiplierSlider.addEventListener('input', (e) => {
    speedMultiplierValue.textContent = e.target.value + '%';
    SPEED_MULTIPLIER = parseInt(e.target.value);
    updateSpeedPreview();
});

speedMultiplierSlider.addEventListener('change', async (e) => {
    SPEED_MULTIPLIER = parseInt(e.target.value);
    localStorage.setItem('handy_speed_multiplier', SPEED_MULTIPLIER);
    updateSpeedPreview();
    // Apply new speed immediately to device using stored base speed
    if (api.isConnected) {
        const scaledSpeed = applySpeedMultiplier(currentBaseSpeed);
        await api.setSpeed(scaledSpeed);
        showFeedback(`Speed: ${scaledSpeed}%`, 'success');
    }
});

// Safety slider
safetyMaxSlider.addEventListener('input', (e) => {
    safetyMaxValue.textContent = e.target.value;
});

safetyMaxSlider.addEventListener('change', async (e) => {
    SAFETY_MAX = parseInt(e.target.value);
    localStorage.setItem('handy_safety_max', SAFETY_MAX);
    // Update the stroke max slider's max attribute and value
    strokeMaxSlider.max = SAFETY_MAX;
    // Force current max to respect new safety limit
    const newMax = Math.min(parseInt(strokeMaxSlider.value), SAFETY_MAX);
    strokeMaxSlider.value = newMax;
    strokeMaxValue.textContent = newMax;
    // Apply immediately to device
    if (api.isConnected) {
        const currentMin = parseInt(strokeMinSlider.value);
        await api.setStrokeRange(currentMin, newMax);
        showFeedback(`Stroke: ${currentMin}-${newMax}%`, 'success');
    }
});

// Enter key to connect
connectionKeyInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') connect();
});

// Init - check for saved connection key and settings
function init() {
    const savedKey = api.loadConnectionKey();
    if (savedKey) {
        connectionKeyInput.value = savedKey;
    }
    
    // Load speed multiplier setting
    const savedSpeedMultiplier = localStorage.getItem('handy_speed_multiplier');
    if (savedSpeedMultiplier) {
        SPEED_MULTIPLIER = parseInt(savedSpeedMultiplier);
        speedMultiplierSlider.value = SPEED_MULTIPLIER;
        speedMultiplierValue.textContent = SPEED_MULTIPLIER + '%';
        updateSpeedPreview();
    }
    
    // Load safety max setting
    const savedSafetyMax = localStorage.getItem('handy_safety_max');
    if (savedSafetyMax) {
        SAFETY_MAX = parseInt(savedSafetyMax);
        safetyMaxSlider.value = SAFETY_MAX;
        safetyMaxValue.textContent = SAFETY_MAX;
    }
    
    // Apply safety limit to stroke max slider
    strokeMaxSlider.max = SAFETY_MAX;
    
    showSetup();
}

init();
