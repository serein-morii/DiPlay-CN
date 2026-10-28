#!/usr/bin/env python3
"""Write site/assets/downloads.json with the APK downloads of every published release.

The Pages workflow runs this before each deploy. The pages show the total only when the file
exists, so a failed GitHub API call leaves the count out instead of failing the deploy.
Checksums and source archives are not counted: the in-app updater fetches SHA256SUMS.txt."""
from datetime import datetime, timezone
from pathlib import Path
import json
import os
import urllib.request
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'site' / 'assets' / 'downloads.json'
REPO = os.environ.get('GITHUB_REPOSITORY', 'shihabal3amri/DiPlay')


def releases():
    url = f'https://api.github.com/repos/{REPO}/releases?per_page=100'
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'DiPlay-download-site'}
    if os.environ.get('GITHUB_TOKEN'):
        headers['Authorization'] = 'Bearer ' + os.environ['GITHUB_TOKEN']
    while url:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
            page = json.load(response)
            links = response.headers.get('Link', '')
        yield from page
        url = next((part.split(';')[0].strip(' <>') for part in links.split(',') if 'rel="next"' in part), None)


try:
    published = [release for release in releases() if not release['draft']]
except Exception as error:
    print(f'::warning::Download count unavailable, deploying without it: {error}')
    raise SystemExit(0)
total = sum(asset['download_count'] for release in published for asset in release['assets'] if asset['name'].endswith('.apk'))
OUT.write_text(json.dumps({
    'apkDownloads': total,
    'releases': len(published),
    'updated': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
}) + '\n', encoding='utf-8')
print(f'{total} APK downloads across {len(published)} releases')
