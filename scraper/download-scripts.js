import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import https from 'https';
import http from 'http';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FUNSCRIPTS_DIR = path.join(__dirname, '..', 'funscripts');

// Ensure funscripts directory exists
if (!fs.existsSync(FUNSCRIPTS_DIR)) {
    fs.mkdirSync(FUNSCRIPTS_DIR, { recursive: true });
}

// Download file helper
function downloadFile(url, destPath) {
    return new Promise((resolve, reject) => {
        const protocol = url.startsWith('https') ? https : http;
        const file = fs.createWriteStream(destPath);
        
        protocol.get(url, (response) => {
            if (response.statusCode === 302 || response.statusCode === 301) {
                // Follow redirect
                downloadFile(response.headers.location, destPath)
                    .then(resolve)
                    .catch(reject);
                return;
            }
            
            response.pipe(file);
            file.on('finish', () => {
                file.close();
                resolve(true);
            });
        }).on('error', (err) => {
            fs.unlink(destPath, () => {});
            reject(err);
        });
    });
}

async function scrapeAllScripts() {
    console.log('Starting FunScriptHub downloader...');
    
    const browser = await chromium.launch({ 
        headless: true,
        slowMo: 50
    });
    
    const context = await browser.newContext({
        acceptDownloads: true
    });
    
    const page = await context.newPage();
    
    // First, get total pages by fetching page 1
    let allVideos = [];
    let currentPage = 1;
    let hasMore = true;
    
    console.log('Fetching video list from API...');
    
    while (hasMore && currentPage <= 4) { // Limit to ~100 videos (4 pages x 30)
        try {
            const response = await page.goto(
                `https://www.funscripthub.com/app/openapi/getVideos?page=${currentPage}`,
                { waitUntil: 'networkidle' }
            );
            
            const text = await page.textContent('body');
            const videos = JSON.parse(text);
            
            if (videos.length === 0) {
                hasMore = false;
            } else {
                allVideos.push(...videos);
                console.log(`Page ${currentPage}: Found ${videos.length} videos (total: ${allVideos.length})`);
                currentPage++;
            }
        } catch (e) {
            console.error(`Error fetching page ${currentPage}:`, e.message);
            hasMore = false;
        }
    }
    
    console.log(`\nTotal videos found: ${allVideos.length}`);
    
    // Save video list
    fs.writeFileSync(
        path.join(__dirname, 'all-videos.json'),
        JSON.stringify(allVideos, null, 2)
    );
    
    // Now visit each detail page and find download links
    let downloadedCount = 0;
    let errorCount = 0;
    
    const maxVideos = Math.min(allVideos.length, 100);
    for (let i = 0; i < maxVideos; i++) {
        const video = allVideos[i];
        console.log(`\n[${i + 1}/${maxVideos}] Processing: ${video.title}`);
        
        try {
            // Navigate to detail page
            await page.goto(`https://www.funscripthub.com/detail?id=${video.id}`, {
                waitUntil: 'networkidle',
                timeout: 30000
            });
            
            await page.waitForTimeout(2000);
            
            // Find all download links
            const downloadLinks = await page.evaluate(() => {
                const links = Array.from(document.querySelectorAll('a'));
                return links
                    .filter(a => {
                        const href = a.href || '';
                        const text = a.textContent?.toLowerCase() || '';
                        return href.includes('.funscript') || 
                               href.includes('download') ||
                               text.includes('download') ||
                               text.includes('.funscript');
                    })
                    .map(a => ({
                        href: a.href,
                        text: a.textContent?.trim() || ''
                    }));
            });
            
            // Also look for direct .funscript file links
            const allLinks = await page.evaluate(() => {
                return Array.from(document.querySelectorAll('a'))
                    .map(a => a.href)
                    .filter(href => href && href.includes('.funscript'));
            });
            
            const funscriptLinks = [...new Set([
                ...downloadLinks.map(l => l.href),
                ...allLinks
            ])].filter(url => url.includes('.funscript'));
            
            if (funscriptLinks.length > 0) {
                console.log(`  Found ${funscriptLinks.length} funscript links`);
                
                for (const url of funscriptLinks) {
                    try {
                        const filename = decodeURIComponent(url.split('/').pop().split('?')[0]);
                        const safeName = filename.replace(/[<>:"/\\|?*]/g, '_');
                        const destPath = path.join(FUNSCRIPTS_DIR, safeName);
                        
                        if (!fs.existsSync(destPath)) {
                            await downloadFile(url, destPath);
                            console.log(`  Downloaded: ${safeName}`);
                            downloadedCount++;
                        } else {
                            console.log(`  Skipped (exists): ${safeName}`);
                        }
                    } catch (e) {
                        console.error(`  Error downloading: ${e.message}`);
                        errorCount++;
                    }
                }
            } else {
                // Try to click download buttons
                const downloadBtns = await page.$$('button:has-text("Download"), a:has-text("Download")');
                if (downloadBtns.length > 0) {
                    console.log(`  Found ${downloadBtns.length} download buttons`);
                }
            }
            
        } catch (e) {
            console.error(`  Error processing video ${video.id}: ${e.message}`);
            errorCount++;
        }
        
        // Small delay between requests
        await page.waitForTimeout(500);
    }
    
    await browser.close();
    
    console.log('\n========================================');
    console.log(`Scraping complete!`);
    console.log(`Downloaded: ${downloadedCount} scripts`);
    console.log(`Errors: ${errorCount}`);
    console.log(`Scripts saved to: ${FUNSCRIPTS_DIR}`);
}

scrapeAllScripts().catch(console.error);
