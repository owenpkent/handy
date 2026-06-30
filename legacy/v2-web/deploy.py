#!/usr/bin/env python3
"""
Deploy v2-web to Cloudflare Pages (SmartDong)

Usage:
    python deploy.py

Or manually:
    npx wrangler pages deploy . --project-name smartdong
"""

import subprocess
import os
import sys

PROJECT_NAME = 'smartdong'

def main():
    print("🚀 SmartDong Deployment")
    print()
    
    script_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Deploy using wrangler
    print(f"🚀 Deploying to Cloudflare Pages...")
    deploy_cmd = f'npx wrangler pages deploy . --project-name {PROJECT_NAME} --commit-dirty=true'
    
    result = subprocess.run(deploy_cmd, shell=True, cwd=script_dir)
    
    if result.returncode != 0:
        print("❌ Deployment failed")
        sys.exit(1)
    
    print()
    print("✨ Deployment complete!")
    print(f"📍 URL: https://{PROJECT_NAME}.pages.dev")

if __name__ == '__main__':
    main()
