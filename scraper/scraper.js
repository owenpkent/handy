import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const FUNSCRIPTS_DIR = path.join(__dirname, '..', 'funscripts');

// Ensure funscripts directory exists
if (!fs.existsSync(FUNSCRIPTS_DIR)) {
    fs.mkdirSync(FUNSCRIPTS_DIR, { recursive: true });
}

async function scrape() {
    console.log('Starting FunScriptHub scraper...');
    
    const browser = await chromium.launch({ 
        headless: false,  // Set to true for production
        slowMo: 100
    });
    
    const context = await browser.newContext({
        acceptDownloads: true
    });
    
    const page = await context.newPage();
    
    // Track all API responses to find the scripts endpoint
    const scriptData = [];
    
    page.on('response', async (response) => {
        const url = response.url();
        if (url.includes('script') || url.includes('funscript') || url.includes('api')) {
            console.log(`API Response: ${url}`);
            try {
                const contentType = response.headers()['content-type'] || '';
                if (contentType.includes('json')) {
                    const data = await response.json();
                    console.log('JSON data found:', JSON.stringify(data).substring(0, 200));
                    if (Array.isArray(data)) {
                        scriptData.push(...data);
                    } else if (data.scripts || data.data || data.results) {
                        scriptData.push(...(data.scripts || data.data || data.results));
                    }
                }
            } catch (e) {
                // Not JSON, skip
            }
        }
    });
    
    // Navigate to the main page
    console.log('Navigating to FunScriptHub...');
    await page.goto('https://www.funscripthub.com/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(3000);
    
    // Take a screenshot for debugging
    await page.screenshot({ path: path.join(__dirname, 'screenshot-home.png') });
    console.log('Screenshot saved: screenshot-home.png');
    
    // Try to find and click on scripts/browse link
    const linksToTry = [
        'text=Scripts',
        'text=Browse',
        'text=Library',
        'text=Database',
        'a[href*="script"]',
        'a[href*="browse"]'
    ];
    
    for (const selector of linksToTry) {
        try {
            const link = await page.$(selector);
            if (link) {
                console.log(`Found link: ${selector}`);
                await link.click();
                await page.waitForTimeout(2000);
                await page.screenshot({ path: path.join(__dirname, 'screenshot-scripts.png') });
                break;
            }
        } catch (e) {
            // Continue to next selector
        }
    }
    
    // Wait for content to load
    await page.waitForTimeout(5000);
    
    // Get all links on the page
    const allLinks = await page.evaluate(() => {
        const links = Array.from(document.querySelectorAll('a'));
        return links.map(a => ({
            href: a.href,
            text: a.textContent?.trim() || ''
        }));
    });
    
    console.log('Found links:', allLinks.length);
    fs.writeFileSync(
        path.join(__dirname, 'found-links.json'), 
        JSON.stringify(allLinks, null, 2)
    );
    
    // Look for download links
    const downloadLinks = allLinks.filter(l => 
        l.href.includes('.funscript') || 
        l.href.includes('download') ||
        l.text.toLowerCase().includes('download')
    );
    
    console.log('Download links found:', downloadLinks.length);
    
    // Try to find script cards/items
    const scriptItems = await page.$$('[class*="script"], [class*="card"], [class*="item"]');
    console.log(`Found ${scriptItems.length} potential script items`);
    
    // Get page content for analysis
    const pageContent = await page.content();
    fs.writeFileSync(path.join(__dirname, 'page-content.html'), pageContent);
    console.log('Page content saved to page-content.html');
    
    // Log collected script data
    if (scriptData.length > 0) {
        fs.writeFileSync(
            path.join(__dirname, 'script-data.json'),
            JSON.stringify(scriptData, null, 2)
        );
        console.log(`Saved ${scriptData.length} script entries to script-data.json`);
    }
    
    console.log('\n--- Manual Inspection Required ---');
    console.log('The browser will stay open for 30 seconds for manual inspection.');
    console.log('Check the console for API responses and examine the page structure.');
    
    await page.waitForTimeout(30000);
    
    await browser.close();
    console.log('Scraper finished.');
}

scrape().catch(console.error);
