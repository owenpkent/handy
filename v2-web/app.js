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
const speedMultiplierInput = document.getElementById('speedMultiplierInput');
let SPEED_MULTIPLIER = 100; // Percentage (10-100)

// Global limits
const globalMinSlider = document.getElementById('globalMin');
const globalMinInput = document.getElementById('globalMinInput');
const globalMaxSlider = document.getElementById('globalMax');
const globalMaxInput = document.getElementById('globalMaxInput');
const speedLimitSlider = document.getElementById('speedLimit');
const speedLimitInput = document.getElementById('speedLimitInput');
let GLOBAL_MIN = 0;
let GLOBAL_MAX = 100;
let SPEED_LIMIT = 100;

// DOM Elements
const setupPanel = document.getElementById('setupPanel');
const controlPanel = document.getElementById('controlPanel');
const settingsPanel = document.getElementById('settingsPanel');
const connectionKeyInput = document.getElementById('connectionKey');
const connectBtn = document.getElementById('connectBtn');
const disconnectBtn = document.getElementById('disconnectBtn');
const startBtn = document.getElementById('startBtn');
const stopBtn = document.getElementById('stopBtn');
const strokeMinSlider = document.getElementById('strokeMin');
const strokeMaxSlider = document.getElementById('strokeMax');
const strokeMinInput = document.getElementById('strokeMinInput');
const strokeMaxInput = document.getElementById('strokeMaxInput');
const presetBtns = document.querySelectorAll('.preset-btn');
const tryHrppBtn = document.getElementById('tryHrppBtn');
const statusDot = document.getElementById('statusDot');
const statusText = document.getElementById('statusText');
const menuBtn = document.getElementById('menuBtn');
const closeSettings = document.getElementById('closeSettings');
const toggleDebugBtn = document.getElementById('toggleDebug');
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
    settingsPanel.classList.add('hidden');
    menuBtn.classList.add('hidden');
    setStatus('disconnected');
}

function showControls() {
    setupPanel.classList.add('hidden');
    controlPanel.classList.remove('hidden');
    menuBtn.classList.remove('hidden');
    setStatus('connected');
}

// Note: Battery endpoint not available in V2 REST API
// The Handy 2 Pro has battery but it's not exposed via /battery endpoint

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
    // Apply global limits
    const limited = applyGlobalLimits(min, max);
    try {
        await api.setStrokeRange(limited.min, limited.max);
    } catch (error) {
        showFeedback(error.message, 'error');
    }
}

function applyGlobalLimits(min, max) {
    // Enforce global min/max limits
    return {
        min: Math.max(min, GLOBAL_MIN),
        max: Math.min(max, GLOBAL_MAX)
    };
}

function applySpeedMultiplier(speed) {
    // Scale speed by multiplier (10-100%), then cap to speed limit
    return Math.min(Math.round(speed * (SPEED_MULTIPLIER / 100)), SPEED_LIMIT);
}

async function applyPreset(speed, position, btn) {
    const speedVal = SPEED_VALUES[speed];
    const posVal = POSITION_VALUES[position];
    
    if (!speedVal || !posVal) return;
    
    // Scale preset within global limits
    const globalRange = GLOBAL_MAX - GLOBAL_MIN;
    const scaledMin = Math.round(GLOBAL_MIN + (posVal.min / 100) * globalRange);
    const scaledMax = Math.round(GLOBAL_MIN + (posVal.max / 100) * globalRange);
    
    // Update sliders to match scaled preset
    strokeMinSlider.value = scaledMin;
    strokeMaxSlider.value = scaledMax;
    strokeMinInput.value = scaledMin;
    strokeMaxInput.value = scaledMax;
    
    // Mark button as active
    clearActivePreset();
    btn.classList.add('active');
    
    // Store base speed and apply multiplier
    currentBaseSpeed = speedVal;
    const scaledSpeed = applySpeedMultiplier(speedVal);
    
    try {
        // Set stroke range (both scaled by safety limit)
        await api.setStrokeRange(scaledMin, scaledMax);
        // Start motion
        await api.start();
        // Set speed AFTER starting (with multiplier applied)
        await api.setSpeed(scaledSpeed);
        showFeedback(`${speed} ${position} (${scaledMin}-${scaledMax}%)`, 'success');
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
    strokeMinInput.value = e.target.value;
    if (parseInt(e.target.value) >= parseInt(strokeMaxSlider.value)) {
        strokeMaxSlider.value = parseInt(e.target.value) + 10;
        strokeMaxInput.value = strokeMaxSlider.value;
    }
});

strokeMinSlider.addEventListener('change', (e) => {
    clearActivePreset();
    setStrokeRange(parseInt(strokeMinSlider.value), parseInt(strokeMaxSlider.value));
});

strokeMinInput.addEventListener('change', (e) => {
    let val = parseInt(e.target.value) || 0;
    val = Math.max(0, Math.min(parseInt(strokeMaxSlider.value) - 1, val));
    e.target.value = val;
    strokeMinSlider.value = val;
    clearActivePreset();
    setStrokeRange(val, parseInt(strokeMaxSlider.value));
});

strokeMaxSlider.addEventListener('input', (e) => {
    strokeMaxInput.value = e.target.value;
    if (parseInt(e.target.value) <= parseInt(strokeMinSlider.value)) {
        strokeMinSlider.value = parseInt(e.target.value) - 10;
        strokeMinInput.value = strokeMinSlider.value;
    }
});

strokeMaxSlider.addEventListener('change', (e) => {
    clearActivePreset();
    setStrokeRange(parseInt(strokeMinSlider.value), parseInt(strokeMaxSlider.value));
});

strokeMaxInput.addEventListener('change', (e) => {
    let val = parseInt(e.target.value) || 0;
    val = Math.max(parseInt(strokeMinSlider.value) + 1, Math.min(GLOBAL_MAX, val));
    e.target.value = val;
    strokeMaxSlider.value = val;
    clearActivePreset();
    setStrokeRange(parseInt(strokeMinSlider.value), val);
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

// Hamburger menu toggle
menuBtn.addEventListener('click', () => {
    settingsPanel.classList.remove('hidden');
    controlPanel.classList.add('hidden');
});

closeSettings.addEventListener('click', () => {
    settingsPanel.classList.add('hidden');
    controlPanel.classList.remove('hidden');
});

// Debug panel toggle
toggleDebugBtn.addEventListener('click', () => {
    const isHidden = debugPanel.classList.toggle('hidden');
    toggleDebugBtn.textContent = isHidden ? 'Show Debug' : 'Hide Debug';
});

// Speed multiplier slider
speedMultiplierSlider.addEventListener('input', (e) => {
    speedMultiplierInput.value = e.target.value;
    SPEED_MULTIPLIER = parseInt(e.target.value);
});

speedMultiplierSlider.addEventListener('change', async (e) => {
    SPEED_MULTIPLIER = parseInt(e.target.value);
    localStorage.setItem('handy_speed_multiplier', SPEED_MULTIPLIER);
    try {
        const scaledSpeed = applySpeedMultiplier(currentBaseSpeed);
        await api.setSpeed(scaledSpeed);
        showFeedback(`Speed: ${scaledSpeed}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

// Speed multiplier number input
speedMultiplierInput.addEventListener('change', async (e) => {
    let val = parseInt(e.target.value) || 10;
    val = Math.max(10, Math.min(100, val));
    e.target.value = val;
    speedMultiplierSlider.value = val;
    SPEED_MULTIPLIER = val;
    localStorage.setItem('handy_speed_multiplier', SPEED_MULTIPLIER);
    try {
        const scaledSpeed = applySpeedMultiplier(currentBaseSpeed);
        await api.setSpeed(scaledSpeed);
        showFeedback(`Speed: ${scaledSpeed}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

// Global Min slider
globalMinSlider.addEventListener('input', (e) => {
    globalMinInput.value = e.target.value;
});

globalMinSlider.addEventListener('change', async (e) => {
    GLOBAL_MIN = parseInt(e.target.value);
    localStorage.setItem('handy_global_min', GLOBAL_MIN);
    strokeMinSlider.min = GLOBAL_MIN;
    const newMin = Math.max(parseInt(strokeMinSlider.value), GLOBAL_MIN);
    strokeMinSlider.value = newMin;
    strokeMinInput.value = newMin;
    try {
        const currentMax = parseInt(strokeMaxSlider.value);
        await api.setStrokeRange(newMin, currentMax);
        showFeedback(`G.Min: ${newMin}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

globalMinInput.addEventListener('change', async (e) => {
    let val = parseInt(e.target.value) || 0;
    val = Math.max(0, Math.min(GLOBAL_MAX - 10, val));
    e.target.value = val;
    globalMinSlider.value = val;
    GLOBAL_MIN = val;
    localStorage.setItem('handy_global_min', GLOBAL_MIN);
    strokeMinSlider.min = GLOBAL_MIN;
    const newMin = Math.max(parseInt(strokeMinSlider.value), GLOBAL_MIN);
    strokeMinSlider.value = newMin;
    strokeMinInput.value = newMin;
    try {
        const currentMax = parseInt(strokeMaxSlider.value);
        await api.setStrokeRange(newMin, currentMax);
        showFeedback(`G.Min: ${newMin}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

// Global Max slider
globalMaxSlider.addEventListener('input', (e) => {
    globalMaxInput.value = e.target.value;
});

globalMaxSlider.addEventListener('change', async (e) => {
    GLOBAL_MAX = parseInt(e.target.value);
    localStorage.setItem('handy_global_max', GLOBAL_MAX);
    strokeMaxSlider.max = GLOBAL_MAX;
    const newMax = Math.min(parseInt(strokeMaxSlider.value), GLOBAL_MAX);
    strokeMaxSlider.value = newMax;
    strokeMaxInput.value = newMax;
    try {
        const currentMin = parseInt(strokeMinSlider.value);
        await api.setStrokeRange(currentMin, newMax);
        showFeedback(`G.Max: ${newMax}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

globalMaxInput.addEventListener('change', async (e) => {
    let val = parseInt(e.target.value) || 100;
    val = Math.max(GLOBAL_MIN + 10, Math.min(100, val));
    e.target.value = val;
    globalMaxSlider.value = val;
    GLOBAL_MAX = val;
    localStorage.setItem('handy_global_max', GLOBAL_MAX);
    strokeMaxSlider.max = GLOBAL_MAX;
    const newMax = Math.min(parseInt(strokeMaxSlider.value), GLOBAL_MAX);
    strokeMaxSlider.value = newMax;
    strokeMaxInput.value = newMax;
    try {
        const currentMin = parseInt(strokeMinSlider.value);
        await api.setStrokeRange(currentMin, newMax);
        showFeedback(`G.Max: ${newMax}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

// Speed limit slider
speedLimitSlider.addEventListener('input', (e) => {
    speedLimitInput.value = e.target.value;
    SPEED_LIMIT = parseInt(e.target.value);
});

speedLimitSlider.addEventListener('change', async (e) => {
    SPEED_LIMIT = parseInt(e.target.value);
    localStorage.setItem('handy_speed_limit', SPEED_LIMIT);
    try {
        // If current speed exceeds new limit, reduce it
        const scaledSpeed = applySpeedMultiplier(currentBaseSpeed);
        await api.setSpeed(scaledSpeed);
        showFeedback(`Spd.Lim: ${SPEED_LIMIT}% → Speed: ${scaledSpeed}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
    }
});

speedLimitInput.addEventListener('change', async (e) => {
    let val = parseInt(e.target.value) || 1;
    val = Math.max(1, Math.min(100, val));
    e.target.value = val;
    speedLimitSlider.value = val;
    SPEED_LIMIT = val;
    localStorage.setItem('handy_speed_limit', SPEED_LIMIT);
    try {
        const scaledSpeed = applySpeedMultiplier(currentBaseSpeed);
        await api.setSpeed(scaledSpeed);
        showFeedback(`Spd.Lim: ${SPEED_LIMIT}% → Speed: ${scaledSpeed}%`, 'success');
    } catch (err) {
        showFeedback(err.message, 'error');
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
        speedMultiplierInput.value = SPEED_MULTIPLIER;
    }
    
    // Load global min setting
    const savedGlobalMin = localStorage.getItem('handy_global_min');
    if (savedGlobalMin) {
        GLOBAL_MIN = parseInt(savedGlobalMin);
        globalMinSlider.value = GLOBAL_MIN;
        globalMinInput.value = GLOBAL_MIN;
    }
    
    // Load global max setting
    const savedGlobalMax = localStorage.getItem('handy_global_max');
    if (savedGlobalMax) {
        GLOBAL_MAX = parseInt(savedGlobalMax);
        globalMaxSlider.value = GLOBAL_MAX;
        globalMaxInput.value = GLOBAL_MAX;
    }

    // Load speed limit setting
    const savedSpeedLimit = localStorage.getItem('handy_speed_limit');
    if (savedSpeedLimit) {
        SPEED_LIMIT = parseInt(savedSpeedLimit);
        speedLimitSlider.value = SPEED_LIMIT;
        speedLimitInput.value = SPEED_LIMIT;
    }
    
    // Apply global limits to stroke sliders
    strokeMinSlider.min = GLOBAL_MIN;
    strokeMaxSlider.max = GLOBAL_MAX;
    
    showSetup();
}

init();
