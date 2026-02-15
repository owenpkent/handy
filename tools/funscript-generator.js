#!/usr/bin/env node
/**
 * FunScript Generator CLI
 * 
 * Generates funscript files with various patterns.
 * 
 * Usage:
 *   node funscript-generator.js [options]
 * 
 * Options:
 *   --pattern <name>    Pattern type: stroke, wave, random, pulse, tease (default: stroke)
 *   --duration <ms>     Duration in milliseconds (default: 60000 = 1 minute)
 *   --speed <bpm>       Strokes per minute (default: 60)
 *   --min <0-100>       Minimum position (default: 0)
 *   --max <0-100>       Maximum position (default: 100)
 *   --output <file>     Output filename (default: generated.funscript)
 *   --help              Show this help
 * 
 * Examples:
 *   node funscript-generator.js --pattern stroke --speed 120 --duration 300000
 *   node funscript-generator.js --pattern wave --min 20 --max 80
 *   node funscript-generator.js --pattern random --speed 90
 */

const fs = require('fs');
const path = require('path');

// Parse command line arguments
function parseArgs() {
    const args = process.argv.slice(2);
    const options = {
        pattern: 'stroke',
        duration: 60000,
        speed: 60,
        min: 0,
        max: 100,
        output: 'generated.funscript'
    };

    for (let i = 0; i < args.length; i++) {
        switch (args[i]) {
            case '--pattern':
                options.pattern = args[++i];
                break;
            case '--duration':
                options.duration = parseInt(args[++i]);
                break;
            case '--speed':
                options.speed = parseInt(args[++i]);
                break;
            case '--min':
                options.min = parseInt(args[++i]);
                break;
            case '--max':
                options.max = parseInt(args[++i]);
                break;
            case '--output':
                options.output = args[++i];
                break;
            case '--help':
                showHelp();
                process.exit(0);
        }
    }

    return options;
}

function showHelp() {
    console.log(`
FunScript Generator CLI

Generates funscript files with various patterns.

Usage:
  node funscript-generator.js [options]

Options:
  --pattern <name>    Pattern type (default: stroke)
                      - stroke: Simple up/down strokes
                      - wave: Smooth sine wave motion
                      - random: Random positions and timing
                      - pulse: Quick pulses with pauses
                      - tease: Slow buildup with occasional fast strokes
                      - edge: Fast strokes with sudden stops
  --duration <ms>     Duration in milliseconds (default: 60000)
  --speed <bpm>       Strokes per minute (default: 60)
  --min <0-100>       Minimum position (default: 0)
  --max <0-100>       Maximum position (default: 100)
  --output <file>     Output filename (default: generated.funscript)
  --help              Show this help

Examples:
  node funscript-generator.js --pattern stroke --speed 120 --duration 300000
  node funscript-generator.js --pattern wave --min 20 --max 80
  node funscript-generator.js --pattern random --speed 90
`);
}

// Pattern generators
const patterns = {
    // Simple up/down strokes at constant speed
    stroke: (options) => {
        const actions = [];
        const strokeDuration = 60000 / options.speed; // ms per full stroke (up + down)
        const halfStroke = strokeDuration / 2;
        
        let time = 0;
        let pos = options.min;
        
        while (time <= options.duration) {
            actions.push({ at: Math.round(time), pos });
            pos = pos === options.min ? options.max : options.min;
            time += halfStroke;
        }
        
        return actions;
    },

    // Smooth sine wave motion
    wave: (options) => {
        const actions = [];
        const cycleTime = 60000 / options.speed;
        const steps = 20; // Points per cycle for smoothness
        const stepTime = cycleTime / steps;
        const range = options.max - options.min;
        const mid = options.min + range / 2;
        
        let time = 0;
        
        while (time <= options.duration) {
            const angle = (time / cycleTime) * Math.PI * 2;
            const pos = mid + (Math.sin(angle) * range / 2);
            actions.push({ at: Math.round(time), pos: Math.round(pos) });
            time += stepTime;
        }
        
        return actions;
    },

    // Random positions with varying speeds
    random: (options) => {
        const actions = [];
        const avgStrokeTime = 60000 / options.speed;
        const range = options.max - options.min;
        
        let time = 0;
        actions.push({ at: 0, pos: options.min + Math.round(Math.random() * range) });
        
        while (time <= options.duration) {
            // Random time variation (50% to 150% of average)
            const strokeTime = avgStrokeTime * (0.5 + Math.random());
            time += strokeTime;
            
            const pos = options.min + Math.round(Math.random() * range);
            actions.push({ at: Math.round(time), pos });
        }
        
        return actions;
    },

    // Quick pulses with pauses
    pulse: (options) => {
        const actions = [];
        const pulseDuration = 200; // Quick pulse
        const pauseDuration = 60000 / options.speed - pulseDuration * 2;
        
        let time = 0;
        
        while (time <= options.duration) {
            // Start position
            actions.push({ at: Math.round(time), pos: options.min });
            time += pulseDuration;
            
            // Peak
            actions.push({ at: Math.round(time), pos: options.max });
            time += pulseDuration;
            
            // Return
            actions.push({ at: Math.round(time), pos: options.min });
            time += pauseDuration;
        }
        
        return actions;
    },

    // Slow buildup with occasional fast strokes
    tease: (options) => {
        const actions = [];
        const range = options.max - options.min;
        const slowStroke = 2000; // 2 seconds for slow
        const fastStroke = 200;  // 200ms for fast
        
        let time = 0;
        let pos = options.min;
        
        while (time <= options.duration) {
            // 80% slow strokes, 20% fast bursts
            if (Math.random() > 0.2) {
                // Slow tease
                actions.push({ at: Math.round(time), pos });
                pos = pos === options.min ? options.min + range * 0.6 : options.min;
                time += slowStroke;
            } else {
                // Fast burst (3-5 quick strokes)
                const burstCount = 3 + Math.floor(Math.random() * 3);
                for (let i = 0; i < burstCount; i++) {
                    actions.push({ at: Math.round(time), pos: options.min });
                    time += fastStroke;
                    actions.push({ at: Math.round(time), pos: options.max });
                    time += fastStroke;
                }
                pos = options.min;
            }
        }
        
        return actions;
    },

    // Fast strokes with sudden stops (edging pattern)
    edge: (options) => {
        const actions = [];
        const fastStroke = 60000 / (options.speed * 2); // Double speed
        const pauseDuration = 3000; // 3 second pause
        const strokesBeforePause = 10 + Math.floor(Math.random() * 10);
        
        let time = 0;
        let strokeCount = 0;
        let pos = options.min;
        
        while (time <= options.duration) {
            actions.push({ at: Math.round(time), pos });
            pos = pos === options.min ? options.max : options.min;
            time += fastStroke;
            strokeCount++;
            
            // Random pause after some strokes
            if (strokeCount >= strokesBeforePause) {
                // Hold at current position
                actions.push({ at: Math.round(time), pos });
                time += pauseDuration;
                strokeCount = 0;
            }
        }
        
        return actions;
    }
};

// Generate funscript
function generateFunscript(options) {
    const generator = patterns[options.pattern];
    if (!generator) {
        console.error(`Unknown pattern: ${options.pattern}`);
        console.error(`Available patterns: ${Object.keys(patterns).join(', ')}`);
        process.exit(1);
    }

    const actions = generator(options);

    const funscript = {
        version: "1.0",
        inverted: false,
        range: 100,
        metadata: {
            creator: "funscript-generator",
            pattern: options.pattern,
            speed: options.speed,
            duration: options.duration,
            min: options.min,
            max: options.max,
            createdAt: new Date().toISOString()
        },
        actions
    };

    return funscript;
}

// Main
function main() {
    const options = parseArgs();
    
    console.log(`Generating funscript...`);
    console.log(`  Pattern: ${options.pattern}`);
    console.log(`  Duration: ${options.duration}ms (${(options.duration / 1000 / 60).toFixed(1)} min)`);
    console.log(`  Speed: ${options.speed} strokes/min`);
    console.log(`  Range: ${options.min}-${options.max}%`);
    
    const funscript = generateFunscript(options);
    
    const outputPath = options.output.endsWith('.funscript') 
        ? options.output 
        : options.output + '.funscript';
    
    fs.writeFileSync(outputPath, JSON.stringify(funscript, null, 2));
    
    console.log(`\nGenerated ${funscript.actions.length} actions`);
    console.log(`Saved to: ${outputPath}`);
}

main();
