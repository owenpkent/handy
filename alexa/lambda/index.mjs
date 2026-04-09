/**
 * Alexa Lambda Handler for Handy Voice Control
 *
 * Zero dependencies — uses built-in fetch (Node.js 18+).
 * Paste directly into the AWS Lambda inline editor.
 *
 * Environment variables:
 *   HANDY_CONNECTION_KEY  — your Handy device connection key (required)
 *   GLOBAL_MIN            — default global minimum position, 0-100 (optional, default 0)
 *   GLOBAL_MAX            — default global maximum position, 0-100 (optional, default 100)
 *   GLOBAL_MAX_SPEED      — default global maximum speed, 0-100 (optional, default 100)
 *   FUNSCRIPT_BASE_URL    — base URL where funscript files are hosted (optional)
 */

const BASE_URL = 'https://www.handyfeeling.com/api/handy/v2';

// ── Preset definitions ─────────────────────────────────────────────
// All position values are in 0-100 "design space" — they get scaled
// into the global min/max range before being sent to the device.

const SPEED_VALUES = { slow: 25, medium: 50, fast: 80 };

const POSITION_VALUES = {
  top:     { min: 50, max: 90 },
  middle:  { min: 30, max: 70 },
  bottom:  { min: 10, max: 50 },
  full:    { min: 0,  max: 100 },
  natural: { min: 20, max: 80 },
};

// Sensation presets — speed + stroke combos that create distinct feelings.
// Position values are in 0-100 design space (scaled by global limits).
const SENSATION_PRESETS = {
  vibrate:     { speed: 100, min: 45, max: 55,  desc: 'vibrate' },
  pulse:       { speed: 80,  min: 40, max: 60,  desc: 'pulse' },
  tease:       { speed: 15,  min: 75, max: 95,  desc: 'tease' },
  deep:        { speed: 20,  min: 0,  max: 100, desc: 'deep' },
  intense:     { speed: 90,  min: 10, max: 90,  desc: 'intense' },
  edge:        { speed: 70,  min: 60, max: 90,  desc: 'edge' },
  gentle:      { speed: 10,  min: 30, max: 70,  desc: 'gentle' },
  jackhammer:  { speed: 100, min: 0,  max: 100, desc: 'jackhammer' },
};

// Funscript patterns available via HSSP (experimental).
// Each maps to a .funscript file hosted at FUNSCRIPT_BASE_URL.
const FUNSCRIPT_PATTERNS = {
  wave:     'wave.funscript',
  surge:    'surge.funscript',
  heartbeat:'heartbeat.funscript',
  storm:    'storm.funscript',
  breathe:  'breathe.funscript',
};

// ── Handy API helpers ──────────────────────────────────────────────

function connectionKey() {
  const key = process.env.HANDY_CONNECTION_KEY;
  if (!key) throw new Error('HANDY_CONNECTION_KEY not set');
  return key;
}

async function handyRequest(endpoint, method = 'GET', body = null) {
  const options = {
    method,
    headers: {
      'X-Connection-Key': connectionKey(),
      'Accept': 'application/json',
      'Content-Type': 'application/json',
    },
  };
  if (body !== null) options.body = JSON.stringify(body);

  const res = await fetch(`${BASE_URL}${endpoint}`, options);
  const data = await res.json();
  if (!res.ok) throw new Error(data.error?.message || `API ${res.status}`);
  return data;
}

async function ensureHAMPMode() {
  await handyRequest('/mode', 'PUT', { mode: 0 });
}

// ── Global limits ──────────────────────────────────────────────────
// Persistent defaults come from env vars (set once in AWS console).
// Can be temporarily overridden per Alexa session via voice commands.
// Session overrides are lost when the Alexa session ends.

function getGlobalMin(session) {
  if (session.globalMin !== undefined) return session.globalMin;
  return parseInt(process.env.GLOBAL_MIN, 10) || 0;
}

function getGlobalMax(session) {
  if (session.globalMax !== undefined) return session.globalMax;
  return parseInt(process.env.GLOBAL_MAX, 10) || 100;
}

function getGlobalMaxSpeed(session) {
  if (session.globalMaxSpeed !== undefined) return session.globalMaxSpeed;
  const val = parseInt(process.env.GLOBAL_MAX_SPEED, 10);
  return isNaN(val) ? 100 : val;
}

/**
 * Cap a speed value to the global max speed limit.
 */
function capSpeed(speed, session) {
  return Math.min(speed, getGlobalMaxSpeed(session));
}

/**
 * Scale a 0-100 "design space" value into the current global range.
 * e.g. with globalMin=0, globalMax=30: design 0→0, design 100→30, design 50→15
 */
function scaleToGlobal(designValue, session) {
  const gMin = getGlobalMin(session);
  const gMax = getGlobalMax(session);
  const range = gMax - gMin;
  return Math.round(gMin + (designValue / 100) * range);
}

/**
 * Scale a min/max pair from design space into global range.
 */
function scaleRange(designMin, designMax, session) {
  return {
    min: scaleToGlobal(designMin, session),
    max: scaleToGlobal(designMax, session),
  };
}

/**
 * Clamp a raw position value to the current global range.
 * Used for direct "set stroke" commands where the user specifies absolute values.
 */
function clampToGlobal(value, session) {
  const gMin = getGlobalMin(session);
  const gMax = getGlobalMax(session);
  return Math.max(gMin, Math.min(gMax, value));
}

// ── Alexa response builders ────────────────────────────────────────

function say(text, endSession = false, sessionAttributes = {}) {
  return {
    version: '1.0',
    sessionAttributes,
    response: {
      outputSpeech: { type: 'PlainText', text },
      shouldEndSession: endSession,
    },
  };
}

function sayAndEnd(text) {
  return say(text, true);
}

// ── Intent handlers ────────────────────────────────────────────────

async function handleLaunch(session) {
  try {
    const connected = await handyRequest('/connected');
    if (!connected.connected) {
      return say('Handy is not connected. Make sure it is powered on and online.', true);
    }
    await ensureHAMPMode();
    const gMin = getGlobalMin(session);
    const gMax = getGlobalMax(session);
    const gMaxSpeed = getGlobalMaxSpeed(session);
    const speedMsg = gMaxSpeed < 100 ? `, max speed ${gMaxSpeed}` : '';
    return say(`Handy connected. Range ${gMin} to ${gMax}${speedMsg}. What would you like to do?`, false, session);
  } catch (err) {
    return say(`Could not connect to Handy. ${err.message}`, true);
  }
}

async function handleStart(session) {
  try {
    await ensureHAMPMode();
    await handyRequest('/hamp/start', 'PUT');
    return say('Started.', false, session);
  } catch (err) {
    return say(`Could not start. ${err.message}`, false, session);
  }
}

async function handleStop(session) {
  try {
    await handyRequest('/hamp/stop', 'PUT');
    return say('Stopped.', false, session);
  } catch (err) {
    return say(`Could not stop. ${err.message}`, false, session);
  }
}

async function handleSetSpeed(slots, session) {
  const raw = parseInt(slots.speed?.value, 10);
  if (isNaN(raw) || raw < 0 || raw > 100) {
    return say('Speed must be between 0 and 100.', false, session);
  }
  const speed = capSpeed(raw, session);
  try {
    await handyRequest('/hamp/velocity', 'PUT', { velocity: speed });
    session.currentSpeed = speed;
    const cappedMsg = speed < raw ? ` Capped from ${raw}.` : '';
    return say(`Speed set to ${speed} percent.${cappedMsg}`, false, session);
  } catch (err) {
    return say(`Could not set speed. ${err.message}`, false, session);
  }
}

async function handleAdjustSpeed(direction, session) {
  try {
    let current = session.currentSpeed;
    if (current === undefined) {
      const result = await handyRequest('/hamp/velocity');
      current = result.velocity ?? 50;
    }
    const delta = direction === 'up' ? 10 : -10;
    const newSpeed = capSpeed(Math.max(0, Math.min(100, current + delta)), session);
    await handyRequest('/hamp/velocity', 'PUT', { velocity: newSpeed });
    session.currentSpeed = newSpeed;
    return say(`Speed ${direction === 'up' ? 'increased' : 'decreased'} to ${newSpeed} percent.`, false, session);
  } catch (err) {
    return say(`Could not adjust speed. ${err.message}`, false, session);
  }
}

async function handlePreset(slots, session) {
  const speedName = slots.speed_name?.value?.toLowerCase();
  const positionName = slots.position_name?.value?.toLowerCase();

  const speedVal = SPEED_VALUES[speedName];
  const posVal = POSITION_VALUES[positionName];

  if (!speedVal && !posVal) {
    return say('I didn\'t catch that preset. Try something like "slow top" or "fast full".', false, session);
  }

  try {
    await ensureHAMPMode();

    if (posVal) {
      const scaled = scaleRange(posVal.min, posVal.max, session);
      await handyRequest('/slide', 'PUT', scaled);
    }

    await handyRequest('/hamp/start', 'PUT');

    const velocity = capSpeed(speedVal || 50, session);
    await handyRequest('/hamp/velocity', 'PUT', { velocity });
    session.currentSpeed = velocity;

    const label = `${speedName || ''} ${positionName || ''}`.trim();
    const scaled = posVal ? scaleRange(posVal.min, posVal.max, session) : null;
    const rangeMsg = scaled ? ` Range ${scaled.min} to ${scaled.max}.` : '';
    return say(`${label}. Speed ${velocity}.${rangeMsg}`, false, session);
  } catch (err) {
    return say(`Could not apply preset. ${err.message}`, false, session);
  }
}

async function handleSensation(slots, session) {
  const name = slots.sensation_name?.value?.toLowerCase();
  const preset = SENSATION_PRESETS[name];

  if (!preset) {
    const available = Object.keys(SENSATION_PRESETS).join(', ');
    return say(`I don't know that sensation. Try: ${available}.`, false, session);
  }

  try {
    await ensureHAMPMode();

    const scaled = scaleRange(preset.min, preset.max, session);
    const velocity = capSpeed(preset.speed, session);
    await handyRequest('/slide', 'PUT', scaled);
    await handyRequest('/hamp/start', 'PUT');
    await handyRequest('/hamp/velocity', 'PUT', { velocity });
    session.currentSpeed = velocity;

    return say(`${preset.desc}. Speed ${velocity}, range ${scaled.min} to ${scaled.max}.`, false, session);
  } catch (err) {
    return say(`Could not apply sensation. ${err.message}`, false, session);
  }
}

async function handlePattern(slots, session) {
  const name = slots.pattern_name?.value?.toLowerCase();
  const filename = FUNSCRIPT_PATTERNS[name];
  const baseUrl = process.env.FUNSCRIPT_BASE_URL;

  if (!filename) {
    const available = Object.keys(FUNSCRIPT_PATTERNS).join(', ');
    return say(`I don't know that pattern. Try: ${available}.`, false, session);
  }

  if (!baseUrl) {
    return say('Funscript patterns are not configured. Set the FUNSCRIPT_BASE_URL environment variable in Lambda.', false, session);
  }

  const url = `${baseUrl.replace(/\/+$/, '')}/${filename}`;

  try {
    // Set slide to global limits so funscript positions are bounded
    const gMin = getGlobalMin(session);
    const gMax = getGlobalMax(session);
    await handyRequest('/slide', 'PUT', { min: gMin, max: gMax });

    // Switch to HSSP mode
    await handyRequest('/mode', 'PUT', { mode: 1 });

    // Setup with funscript URL
    await handyRequest('/hssp/setup', 'PUT', { url });

    // Get server time for sync
    const serverTime = await handyRequest('/servertime');
    const estimatedServerTime = serverTime.serverTime ?? Date.now();

    // Play starting now
    await handyRequest('/hssp/play', 'PUT', {
      estimatedServerTime,
      startTime: 0,
    });

    return say(`${name} pattern started. Say stop to end.`, false, session);
  } catch (err) {
    // Fall back to HAMP mode on failure
    try { await ensureHAMPMode(); } catch (_) {}
    return say(`Could not play pattern. ${err.message}. This feature is experimental.`, false, session);
  }
}

async function handleSetStroke(slots, session) {
  const rawMin = parseInt(slots.stroke_min?.value, 10);
  const rawMax = parseInt(slots.stroke_max?.value, 10);

  if (isNaN(rawMin) || isNaN(rawMax) || rawMin < 0 || rawMax > 100 || rawMin >= rawMax) {
    return say('Stroke range needs a minimum and maximum between 0 and 100, with minimum less than maximum.', false, session);
  }

  const min = clampToGlobal(rawMin, session);
  const max = clampToGlobal(rawMax, session);

  try {
    await handyRequest('/slide', 'PUT', { min, max });
    const gMax = getGlobalMax(session);
    const clampedMsg = (rawMax > gMax) ? ` Clamped to global max of ${gMax}.` : '';
    return say(`Stroke range set to ${min} to ${max}.${clampedMsg}`, false, session);
  } catch (err) {
    return say(`Could not set stroke range. ${err.message}`, false, session);
  }
}

async function handleSetGlobalMin(slots, session) {
  const value = parseInt(slots.value?.value, 10);
  if (isNaN(value) || value < 0 || value > 100) {
    return say('Global minimum must be between 0 and 100.', false, session);
  }
  const gMax = getGlobalMax(session);
  if (value >= gMax) {
    return say(`Global minimum must be less than the current maximum of ${gMax}.`, false, session);
  }

  session.globalMin = value;

  // Also push to the device immediately
  try {
    const currentSlide = await handyRequest('/slide');
    const newMin = Math.max(currentSlide.min, value);
    await handyRequest('/slide/min', 'PUT', { min: newMin });
    return say(`Global minimum set to ${value}. This lasts until the session ends. To make it permanent, set GLOBAL_MIN in Lambda.`, false, session);
  } catch (err) {
    return say(`Global minimum set to ${value} for this session, but could not update device. ${err.message}`, false, session);
  }
}

async function handleSetGlobalMax(slots, session) {
  const value = parseInt(slots.value?.value, 10);
  if (isNaN(value) || value < 0 || value > 100) {
    return say('Global maximum must be between 0 and 100.', false, session);
  }
  const gMin = getGlobalMin(session);
  if (value <= gMin) {
    return say(`Global maximum must be greater than the current minimum of ${gMin}.`, false, session);
  }

  session.globalMax = value;

  // Also push to the device immediately
  try {
    const currentSlide = await handyRequest('/slide');
    const newMax = Math.min(currentSlide.max, value);
    await handyRequest('/slide/max', 'PUT', { max: newMax });
    return say(`Global maximum set to ${value}. This lasts until the session ends. To make it permanent, set GLOBAL_MAX in Lambda.`, false, session);
  } catch (err) {
    return say(`Global maximum set to ${value} for this session, but could not update device. ${err.message}`, false, session);
  }
}

async function handleSetGlobalMaxSpeed(slots, session) {
  const value = parseInt(slots.value?.value, 10);
  if (isNaN(value) || value < 1 || value > 100) {
    return say('Speed limit must be between 1 and 100.', false, session);
  }

  session.globalMaxSpeed = value;

  // If current speed exceeds the new limit, reduce it immediately
  try {
    if (session.currentSpeed !== undefined && session.currentSpeed > value) {
      await handyRequest('/hamp/velocity', 'PUT', { velocity: value });
      session.currentSpeed = value;
      return say(`Speed limit set to ${value}. Current speed reduced to ${value}. This lasts until the session ends. To make it permanent, set GLOBAL_MAX_SPEED in Lambda.`, false, session);
    }
    return say(`Speed limit set to ${value}. This lasts until the session ends. To make it permanent, set GLOBAL_MAX_SPEED in Lambda.`, false, session);
  } catch (err) {
    return say(`Speed limit set to ${value} for this session, but could not update device. ${err.message}`, false, session);
  }
}

function handleHelp(session) {
  const gMin = getGlobalMin(session);
  const gMax = getGlobalMax(session);
  const gMaxSpeed = getGlobalMaxSpeed(session);
  const speedMsg = gMaxSpeed < 100 ? `, max speed ${gMaxSpeed}` : '';
  return say(
    `Your limits are ${gMin} to ${gMax}${speedMsg}. ` +
    'Commands: start, stop, faster, slower, set speed to a number. ' +
    'Presets: slow top, fast full, medium bottom. ' +
    'Sensations: vibrate, pulse, tease, deep, intense, edge, gentle, jackhammer. ' +
    'Patterns: play wave, play surge, play heartbeat, play storm, play breathe. ' +
    'Limits: set global minimum, set global maximum, or set speed limit.',
    false,
    session
  );
}

// ── Main handler ───────────────────────────────────────────────────

export const handler = async (event) => {
  const request = event.request;
  const session = event.session?.attributes || {};

  switch (request.type) {
    case 'LaunchRequest':
      return handleLaunch(session);

    case 'IntentRequest': {
      const intent = request.intent.name;
      const slots = request.intent.slots || {};

      switch (intent) {
        case 'StartIntent':
          return handleStart(session);
        case 'StopIntent':
          return handleStop(session);
        case 'SetSpeedIntent':
          return handleSetSpeed(slots, session);
        case 'FasterIntent':
          return handleAdjustSpeed('up', session);
        case 'SlowerIntent':
          return handleAdjustSpeed('down', session);
        case 'PresetIntent':
          return handlePreset(slots, session);
        case 'SensationIntent':
          return handleSensation(slots, session);
        case 'PatternIntent':
          return handlePattern(slots, session);
        case 'SetStrokeIntent':
          return handleSetStroke(slots, session);
        case 'SetMinimumIntent':
          return handleSetGlobalMin(slots, session);
        case 'SetMaximumIntent':
          return handleSetGlobalMax(slots, session);
        case 'SetMaxSpeedIntent':
          return handleSetGlobalMaxSpeed(slots, session);
        case 'AMAZON.HelpIntent':
          return handleHelp(session);
        case 'AMAZON.StopIntent':
        case 'AMAZON.CancelIntent':
          try { await handyRequest('/hamp/stop', 'PUT'); } catch (_) {}
          return sayAndEnd('Stopped. Goodbye.');
        case 'AMAZON.FallbackIntent':
          return say('I didn\'t understand that. Say help for a list of commands.', false, session);
        default:
          return say('I didn\'t understand that command.', false, session);
      }
    }

    case 'SessionEndedRequest':
      return say('', true);

    default:
      return say('Something went wrong.', true);
  }
};
